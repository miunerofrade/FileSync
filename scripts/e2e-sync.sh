#!/usr/bin/env bash
# FileSync 第二阶段端到端测试：两个独立客户端目录的手动双向同步
# 用法: 先构建二进制（filesync.exe 或 filesync），再 bash scripts/e2e-sync.sh
set -u
cd "$(dirname "$0")/.." || exit 1

BIN="./filesync.exe"; [ -f "$BIN" ] || BIN="./filesync"
[ -f "$BIN" ] || { echo "错误: 未找到二进制，请先 go build"; exit 1; }
case "$BIN" in /*) ;; *) BIN="$PWD/${BIN#./}";; esac

PORT=$((18200 + RANDOM % 200))
BASE="http://127.0.0.1:$PORT"
WORK=$(mktemp -d) || exit 1
DATA="$WORK/data"
A="$WORK/clientA"
B="$WORK/clientB"
mkdir -p "$A" "$B"
PASS=0 FAIL=0; SRV_PID=""

ok()  { PASS=$((PASS+1)); printf 'PASS  %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf 'FAIL  %s\n' "$1"; }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 —— 期望[$3] 实际[$2]"; fi; }
hashof() { sha256sum "$1" 2>/dev/null | cut -d' ' -f1; }
syncIn() { local d="$1"; shift; (cd "$d" && "$BIN" sync "$@" </dev/null); }

cleanup() {
  [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null
  sleep 0.3
  rm -rf "$WORK" 2>/dev/null
  true
}
trap cleanup EXIT

"$BIN" serve --addr "127.0.0.1:$PORT" --data-dir "$DATA" >"$WORK/server.log" 2>&1 &
SRV_PID=$!
up=0
for _ in $(seq 1 60); do
  curl -sf "$BASE/api/ping" >/dev/null 2>&1 && { up=1; break; }
  sleep 0.1
done
check "服务端启动" "$up" "1"

# ============ init ============
"$BIN" init --server "$BASE" "$A" >"$WORK/initA.log" 2>&1
check "init A" "$?" "0"
[ -f "$A/.filesync.json" ] && ok "A 状态文件已创建" || bad "A 状态文件已创建"

"$BIN" init --server "$BASE" "$A" >/dev/null 2>&1
check "重复 init 被拒绝" "$?" "1"

"$BIN" init --server "http://127.0.0.1:1" "$B" >/dev/null 2>&1
check "init 服务端不可达被拒绝" "$?" "1"
"$BIN" init --server "$BASE" "$B" >/dev/null 2>&1
check "init B" "$?" "0"

mkdir -p "$WORK/lonely"
(cd "$WORK/lonely" && "$BIN" sync </dev/null) >/dev/null 2>&1
check "未初始化目录 sync 被拒绝" "$?" "1"

# ============ A 首次同步（全部为新增本地文件）============
mkdir -p "$A/docs" "$A/notes"
printf 'vA1\n'  > "$A/docs/a1.md"
printf 'vA2a\n' > "$A/docs/a2.md"
printf 'vN1\n'  > "$A/notes/n1.md"
printf 'vT1\n'  > "$A/top.md"
: > "$A/empty.bin"
head -c 65536 /dev/urandom > "$A/blob.bin"

out=$(syncIn "$A")
echo "$out" | grep -q "上传 6, 下载 0" && ok "A 首次同步上传 6" || bad "A 首次同步上传 6 [$out]"
check "服务器 docs/a1.md 内容一致" "$(hashof "$DATA/docs/a1.md")" "$(hashof "$A/docs/a1.md")"
check "服务器 blob.bin 内容一致" "$(hashof "$DATA/blob.bin")" "$(hashof "$A/blob.bin")"
check "服务器 empty.bin 大小" "$(wc -c < "$DATA/empty.bin" | tr -d ' ')" "0"

out=$(syncIn "$A")
echo "$out" | grep -q "上传 0, 下载 0, 无变化 6" && ok "A 重复同步无变化" || bad "A 重复同步无变化 [$out]"

# ============ B 首次同步（一致/冲突/新增混合）============
mkdir -p "$B/docs"
printf 'vA1\n'  > "$B/docs/a1.md"    # 与服务器一致 → 采纳
printf 'vA2b\n' > "$B/docs/a2.md"    # 与服务器不同且无基线 → 冲突
printf 'b only\n' > "$B/b-only.md"   # B 独有 → 上传

out=$(syncIn "$B" --keep remote)
echo "$out" | grep -q "上传 1, 下载 4, 无变化 1, 冲突解决 1" && ok "B 首次同步统计" || bad "B 首次同步统计 [$out]"
check "B a2 采用远端版本" "$(hashof "$B/docs/a2.md")" "$(hashof "$A/docs/a2.md")"
check "服务器收到 b-only.md" "$(hashof "$DATA/b-only.md")" "$(hashof "$B/b-only.md")"
check "B 下载 empty.bin" "$(wc -c < "$B/empty.bin" | tr -d ' ')" "0"
check "B 下载 blob.bin 完整" "$(hashof "$B/blob.bin")" "$(hashof "$A/blob.bin")"

# ============ A→B 传播 ============
printf 'vA1b\n' > "$A/docs/a1.md"
out=$(syncIn "$A")
# A 上传 a1 的同时，会把 B 上一轮新增的 b-only.md 下载下来（双向同步）
echo "$out" | grep -q "上传 1, 下载 1" && ok "A 修改后上传 1 并拉取 B 新增文件" || bad "A 修改后上传 1 并拉取 B 新增文件 [$out]"
out=$(syncIn "$B")
echo "$out" | grep -q "上传 0, 下载 1" && ok "B 检测到服务器变化并下载" || bad "B 检测下载 [$out]"
check "B a1 更新为 A 的版本" "$(hashof "$B/docs/a1.md")" "$(hashof "$A/docs/a1.md")"

# ============ B→A 传播 ============
printf 'vN1b\n' > "$B/notes/n1.md"
out=$(syncIn "$B")
echo "$out" | grep -q "上传 1, 下载 0" && ok "B 修改后上传 1" || bad "B 修改后上传 1 [$out]"
out=$(syncIn "$A")
echo "$out" | grep -q "上传 0, 下载 1" && ok "A 检测到服务器变化并下载" || bad "A 检测下载 [$out]"
check "A n1 更新为 B 的版本" "$(hashof "$A/notes/n1.md")" "$(hashof "$B/notes/n1.md")"

# ============ 单文件/子目录同步 ============
mkdir -p "$A/misc"
printf 'a5\n' > "$A/docs/a5.md"
printf 'm1\n' > "$A/misc/m1.md"
out=$(syncIn "$A" docs/a5.md)
echo "$out" | grep -q "上传 1, 下载 0" && ok "单文件同步只上传指定文件" || bad "单文件同步 [$out]"
ls "$DATA/misc/m1.md" >/dev/null 2>&1 && bad "未指定的文件未被同步" || ok "未指定的文件未被同步"

printf 'a6\n' > "$A/docs/a6.md"
out=$(syncIn "$A" docs)
echo "$out" | grep -q "上传 1, 下载 0" && ok "子目录同步只上传该子树" || bad "子目录同步 [$out]"
ls "$DATA/misc/m1.md" >/dev/null 2>&1 && bad "子目录外文件未被同步" || ok "子目录外文件未被同步"

out=$(syncIn "$A")
echo "$out" | grep -q "上传 1" && ok "全量同步补齐剩余文件" || bad "全量同步补齐 [$out]"
check "服务器 misc/m1.md 一致" "$(hashof "$DATA/misc/m1.md")" "$(hashof "$A/misc/m1.md")"

# ============ 冲突：A 先上传，B 保留远端 ============
printf 'vT2A\n' > "$A/top.md"
printf 'vT2B\n' > "$B/top.md"
out=$(syncIn "$A")
echo "$out" | grep -q "上传 1" && ok "冲突前 A 先上传" || bad "冲突前 A 先上传 [$out]"
out=$(syncIn "$B" --keep remote)
echo "$out" | grep -q "冲突解决 1" && ok "B 冲突解决(保留远端)" || bad "B 冲突解决(保留远端) [$out]"
check "B top.md 采用 A 的版本" "$(hashof "$B/top.md")" "$(hashof "$A/top.md")"

# ============ 冲突：B 先上传，A 保留本地 ============
printf 'vA1c\n' > "$A/docs/a1.md"
printf 'vA1d\n' > "$B/docs/a1.md"
out=$(syncIn "$B")
echo "$out" | grep -q "上传 1" && ok "冲突前 B 先上传" || bad "冲突前 B 先上传 [$out]"
out=$(syncIn "$A" --keep local)
echo "$out" | grep -q "冲突解决 1" && ok "A 冲突解决(保留本地)" || bad "A 冲突解决(保留本地) [$out]"
check "服务器采用 A 的版本" "$(hashof "$DATA/docs/a1.md")" "$(hashof "$A/docs/a1.md")"
out=$(syncIn "$B")
echo "$out" | grep -q "上传 0, 下载 1" && ok "B 随后自动收敛到服务器版本" || bad "B 收敛 [$out]"
check "B a1 与 A 一致" "$(hashof "$B/docs/a1.md")" "$(hashof "$A/docs/a1.md")"

# ============ 冲突：非交互跳过（不静默覆盖），随后 --keep local ============
head -c 32768 /dev/urandom > "$A/blob.bin"
head -c 32768 /dev/urandom > "$B/blob.bin"
out=$(syncIn "$A")
echo "$out" | grep -q "上传 1" && ok "A 更新 blob" || bad "A 更新 blob [$out]"
bbefore=$(hashof "$B/blob.bin")
out=$(syncIn "$B" 2>&1); rc=$?
check "非交互冲突退出码 1" "$rc" "1"
echo "$out" | grep -q "冲突未解决" && ok "非交互冲突报告" || bad "非交互冲突报告 [$out]"
check "B blob 未被静默覆盖" "$(hashof "$B/blob.bin")" "$bbefore"
out=$(syncIn "$B" --keep local)
echo "$out" | grep -q "冲突解决 1" && ok "B 保留本地解决冲突" || bad "B 保留本地 [$out]"
check "服务器采用 B 的 blob" "$(hashof "$DATA/blob.bin")" "$(hashof "$B/blob.bin")"

# ============ 删除不传播 ============
cp "$A/notes/n1.md" "$WORK/n1.keep"
rm "$A/notes/n1.md"
out=$(syncIn "$A"); rc=$?
check "本地删除后同步退出码 0" "$rc" "0"
echo "$out" | grep -q "本地已删除" && ok "报告本地删除分歧" || bad "报告本地删除分歧 [$out]"
[ -f "$DATA/notes/n1.md" ] && ok "服务器文件保留" || bad "服务器文件保留"
out=$(syncIn "$B")
echo "$out" | grep -q "上传 0, 下载 0" && ok "另一端不受影响" || bad "另一端不受影响 [$out]"
cp "$WORK/n1.keep" "$A/notes/n1.md"
out=$(syncIn "$A")
echo "$out" | grep -q "上传 0, 下载 0" && ok "本地恢复后无变化" || bad "本地恢复后无变化 [$out]"

# ============ 服务器端外部删除分歧 ============
rm "$DATA/docs/a2.md"
out=$(syncIn "$A"); rc=$?
check "服务器外部删除后同步退出码 0" "$rc" "0"
echo "$out" | grep -q "服务器已不存在" && ok "报告服务器删除分歧" || bad "报告服务器删除分歧 [$out]"
[ -f "$A/docs/a2.md" ] && ok "本地文件保留" || bad "本地文件保留"
cp "$A/docs/a2.md" "$DATA/docs/a2.md"
out=$(syncIn "$A")
echo "$out" | grep -q "上传 0, 下载 0" && ok "服务器恢复后无变化" || bad "服务器恢复后无变化 [$out]"

# ============ 中断同步后可收敛（支持重复执行）============
mkdir -p "$A/bulk"
for i in $(seq -w 1 150); do printf 'bulk %s\n' "$i" > "$A/bulk/f$i.md"; done
(cd "$A" && exec "$BIN" sync </dev/null) >"$WORK/bulk1.log" 2>&1 &
BPID=$!
sleep 1
kill $BPID 2>/dev/null
wait $BPID 2>/dev/null
ok "已中断一次 150 文件的全量同步"
syncIn "$A" >/dev/null 2>&1   # 收敛补齐
server_bulk=$("$BIN" ls --server "$BASE" --prefix bulk | grep -c "bulk/f")
check "收敛后服务器拥有全部 150 个文件" "$server_bulk" "150"
check "抽样 f001 一致" "$(hashof "$DATA/bulk/f001.md")" "$(hashof "$A/bulk/f001.md")"
check "抽样 f150 一致" "$(hashof "$DATA/bulk/f150.md")" "$(hashof "$A/bulk/f150.md")"
out=$(syncIn "$A")
echo "$out" | grep -q "上传 0, 下载 0" && ok "中断恢复后重复执行无变化" || bad "中断恢复后重复执行 [$out]"
tmps=$(find "$A" -name '.filesync.json.tmp-*' 2>/dev/null | wc -l | tr -d ' ')
check "状态临时文件已清理" "$tmps" "0"

# ============ 最终收敛与完整性 ============
for _ in 1 2 3; do syncIn "$A" >/dev/null 2>&1; syncIn "$B" >/dev/null 2>&1; done
out=$(syncIn "$A")
echo "$out" | grep -q "上传 0, 下载 0, 无变化" && ok "A 最终无待同步项" || bad "A 最终 [$out]"
out=$(syncIn "$B")
echo "$out" | grep -q "上传 0, 下载 0, 无变化" && ok "B 最终无待同步项" || bad "B 最终 [$out]"

mismatch=0
for f in $(cd "$A" && find . -type f -not -name '.filesync.json*' | sed 's|^\./||'); do
  [ "$(hashof "$A/$f")" = "$(hashof "$DATA/$f")" ] || { mismatch=$((mismatch+1)); echo "  A 不一致: $f"; }
done
check "A 与服务器全部文件一致" "$mismatch" "0"

mismatch=0
for f in $(cd "$B" && find . -type f -not -name '.filesync.json*' | sed 's|^\./||'); do
  [ "$(hashof "$B/$f")" = "$(hashof "$DATA/$f")" ] || { mismatch=$((mismatch+1)); echo "  B 不一致: $f"; }
done
check "B 与服务器全部文件一致" "$mismatch" "0"

# ============ 汇总 ============
echo
echo "----------"
echo "通过 $PASS, 失败 $FAIL"
if [ "$FAIL" -gt 0 ]; then
  echo "--- 服务端日志 ---"
  cat "$WORK/server.log" 2>/dev/null
  exit 1
fi
