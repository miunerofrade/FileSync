#!/usr/bin/env bash
# FileSync 第一阶段端到端测试（Windows Git Bash / Linux 皆可运行）
# 用法: 先构建二进制（filesync.exe 或 filesync），再 bash scripts/e2e.sh
set -u
cd "$(dirname "$0")/.." || exit 1

BIN="./filesync.exe"
[ -f "$BIN" ] || BIN="./filesync"
[ -f "$BIN" ] || { echo "错误: 未找到二进制，请先 go build"; exit 1; }
case "$BIN" in /*) ;; *) BIN="$PWD/${BIN#./}";; esac

PORT=$((18080 + RANDOM % 200))
BASE="http://127.0.0.1:$PORT"
WORK=$(mktemp -d) || exit 1
DATA="$WORK/data"
CLIENT="$WORK/client"
SRV_LOG="$WORK/server.log"
mkdir -p "$CLIENT"
PASS=0 FAIL=0
SRV_PID=""; S2_PID=""

ok()  { PASS=$((PASS+1)); printf 'PASS  %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf 'FAIL  %s\n' "$1"; }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 —— 期望[$3] 实际[$2]"; fi; }
hashof() { sha256sum "$1" 2>/dev/null | cut -d' ' -f1; }
fcount() { find "$DATA" -type f 2>/dev/null | wc -l | tr -d ' '; }

cleanup() {
  [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null
  [ -n "$S2_PID" ] && kill "$S2_PID" 2>/dev/null
  sleep 0.3
  rm -rf "$WORK" 2>/dev/null
  true
}
trap cleanup EXIT

# ============ 启动 ============
"$BIN" serve --addr "127.0.0.1:$PORT" --data-dir "$DATA" >"$SRV_LOG" 2>&1 &
SRV_PID=$!
up=0
for _ in $(seq 1 60); do
  curl -sf "$BASE/api/ping" >/dev/null 2>&1 && { up=1; break; }
  sleep 0.1
done
check "服务端启动 / ping" "$up" "1"

# ============ 测试数据 ============
printf 'hello filesync\n' > "$CLIENT/hello.md"
head -c 65536 /dev/urandom > "$CLIENT/random.bin"
: > "$CLIENT/empty.txt"
printf '# 中文标题\n正文内容。\n' > "$CLIENT/中文 文档.md"
printf 'v1\n' > "$CLIENT/rev.txt"
mkdir -p "$CLIENT/notes/sub"
printf 'nested\n' > "$CLIENT/notes/sub/n.txt"
printf 'outside\n' > "$WORK/secret.txt"

# ============ put / get / ls ============
"$BIN" put --server "$BASE" "$CLIENT/hello.md" docs/hello.md >/dev/null 2>&1
check "put 子目录文件内容一致" "$(hashof "$DATA/docs/hello.md")" "$(hashof "$CLIENT/hello.md")"

"$BIN" put --server "$BASE" "$CLIENT/random.bin" >/dev/null 2>&1
check "put 二进制(默认远端名)" "$(hashof "$DATA/random.bin")" "$(hashof "$CLIENT/random.bin")"

"$BIN" put --server "$BASE" "$CLIENT/empty.txt" empty.txt >/dev/null 2>&1
s=$(wc -c < "$DATA/empty.txt" 2>/dev/null | tr -d ' ')
check "put 空文件" "$s" "0"

"$BIN" put --server "$BASE" "$CLIENT/中文 文档.md" "中文/文档.md" >/dev/null 2>&1
check "put 中文路径" "$(hashof "$DATA/中文/文档.md")" "$(hashof "$CLIENT/中文 文档.md")"

"$BIN" put --server "$BASE" "$CLIENT/rev.txt" rev.txt >/dev/null 2>&1

"$BIN" get --server "$BASE" docs/hello.md "$CLIENT/back-hello.md" >/dev/null 2>&1
check "get 内容一致" "$(hashof "$CLIENT/back-hello.md")" "$(hashof "$CLIENT/hello.md")"

"$BIN" get --server "$BASE" random.bin "$CLIENT/back-bin" >/dev/null 2>&1
check "get 二进制一致" "$(hashof "$CLIENT/back-bin")" "$(hashof "$CLIENT/random.bin")"

"$BIN" get --server "$BASE" empty.txt "$CLIENT/back-empty" >/dev/null 2>&1
s=$(wc -c < "$CLIENT/back-empty" 2>/dev/null | tr -d ' ')
check "get 空文件" "$s" "0"

"$BIN" get --server "$BASE" docs/hello.md "$CLIENT/deep/dir/h.md" >/dev/null 2>&1
[ -f "$CLIENT/deep/dir/h.md" ] && ok "get 自动创建本地目录" || bad "get 自动创建本地目录"

lsout=$("$BIN" ls --server "$BASE" 2>&1)
echo "$lsout" | grep -q "docs/hello.md" && ok "ls 列出子目录文件" || bad "ls 列出子目录文件"
echo "$lsout" | grep -q "中文/文档.md" && ok "ls 列出中文路径" || bad "ls 列出中文路径"
echo "$lsout" | tail -n 1 | grep -q "共 5 个文件" && ok "ls 汇总行" || bad "ls 汇总行"

pf=$("$BIN" ls --server "$BASE" --prefix docs 2>&1)
if echo "$pf" | grep -q "docs/hello.md" && ! echo "$pf" | grep -q "random.bin"; then
  ok "ls --prefix 过滤"
else
  bad "ls --prefix 过滤"
fi

touch "$DATA/.fsync-fake-tmp"
lsout=$("$BIN" ls --server "$BASE" 2>&1)
echo "$lsout" | grep -q "fsync-fake-tmp" && bad "ls 跳过临时文件" || ok "ls 跳过临时文件"
rm -f "$DATA/.fsync-fake-tmp"

# ============ 默认不覆盖 ============
out=$("$BIN" put --server "$BASE" "$CLIENT/hello.md" docs/hello.md 2>&1); rc=$?
check "重复 put 单文件退出码" "$rc" "1"
echo "$out" | grep -q "已存在" && ok "重复 put 提示已存在" || bad "重复 put 提示已存在 [$out]"

printf 'v2\n' > "$CLIENT/rev.txt"
"$BIN" put --server "$BASE" "$CLIENT/rev.txt" rev.txt >/dev/null 2>&1
check "默认不覆盖远端(退出码)" "$?" "1"
check "远端内容仍为 v1" "$(cat "$DATA/rev.txt" 2>/dev/null)" "v1"

"$BIN" put --server "$BASE" --overwrite "$CLIENT/rev.txt" rev.txt >/dev/null 2>&1
check "--overwrite 上传成功" "$?" "0"
check "远端内容已更新 v2" "$(cat "$DATA/rev.txt" 2>/dev/null)" "v2"

"$BIN" get --server "$BASE" rev.txt "$CLIENT/back-hello.md" >/dev/null 2>&1
check "get 本地已存在默认拒绝" "$?" "1"
check "本地内容未被改动" "$(cat "$CLIENT/back-hello.md" 2>/dev/null)" "hello filesync"

"$BIN" get --server "$BASE" --overwrite rev.txt "$CLIENT/back-hello.md" >/dev/null 2>&1
check "get --overwrite 生效" "$(cat "$CLIENT/back-hello.md" 2>/dev/null)" "v2"

# ============ 目录上传 ============
"$BIN" put --server "$BASE" "$CLIENT/notes" backup >/dev/null 2>&1
check "目录上传退出码" "$?" "0"
check "目录上传嵌套文件一致" "$(hashof "$DATA/backup/sub/n.txt")" "$(hashof "$CLIENT/notes/sub/n.txt")"
[ -d "$DATA/backup/sub" ] && ok "目录结构保留" || bad "目录结构保留"

out=$("$BIN" put --server "$BASE" "$CLIENT/notes" backup 2>&1)
check "目录重复上传退出码(跳过)" "$?" "0"
echo "$out" | grep -q "跳过 1" && ok "目录重复上传跳过计数" || bad "目录重复上传跳过计数 [$out]"

"$BIN" put --server "$BASE" "$CLIENT/notes" >/dev/null 2>&1
check "目录上传默认远端名" "$(hashof "$DATA/notes/sub/n.txt")" "$(hashof "$CLIENT/notes/sub/n.txt")"

# ============ 错误处理 ============
"$BIN" put --server "$BASE" "$CLIENT/nope.md" >/dev/null 2>&1
check "put 本地文件不存在" "$?" "1"
"$BIN" get --server "$BASE" ghost.txt >/dev/null 2>&1
check "get 远端不存在" "$?" "1"
"$BIN" get --server "$BASE" docs >/dev/null 2>&1
check "get 目录被拒绝" "$?" "1"
"$BIN" put --server "$BASE" "$CLIENT/hello.md" "../evil.md" >/dev/null 2>&1
check "客户端拒绝非法远端路径" "$?" "1"
[ ! -e "$WORK/evil.md" ] && ok "根目录外未创建 evil.md" || bad "根目录外未创建 evil.md"

# ============ 路径穿越（绕过客户端直接打 HTTP）============
SUM=$(hashof "$CLIENT/hello.md")
g() { curl -s -o /dev/null -w '%{http_code}' "$BASE/api/get?path=$1"; }
check "get 穿越 ../"      "$(g '../secret.txt')" "400"
check "get 穿越 ..%2F"    "$(g '..%2Fsecret.txt')" "400"
check "get 穿越 %2e%2e"   "$(g '%2e%2e%2fsecret.txt')" "400"
check "get 穿越 a/../.."  "$(g 'a%2F..%2F..%2Fsecret.txt')" "400"
check "get 穿越 docs/../" "$(g 'docs%2F..%2Fsecret.txt')" "400"
check "get 绝对路径"      "$(g '%2Fetc%2Fpasswd')" "400"
check "get 盘符+反斜杠"   "$(g 'C:%5Cwindows%5Cwin.ini')" "400"
check "get 纯 .."         "$(g '..')" "400"
check "get 空 path"       "$(g '')" "400"
check "根目录外文件未被修改" "$(cat "$WORK/secret.txt")" "outside"

p() { curl -s -o /dev/null -w '%{http_code}' -X PUT -H "X-Checksum-SHA256: $SUM" --data-binary @"$CLIENT/hello.md" "$BASE/api/put?path=$1"; }
check "put 穿越 ../"           "$(p '../evil.txt')" "400"
check "put 穿越 ..%2F"         "$(p '..%2Fevil.txt')" "400"
check "put Windows 保留名 CON" "$(p 'CON.txt')" "400"
check "put 冒号(ADS)"          "$(p 'a:b')" "400"
[ ! -e "$WORK/evil.txt" ] && ok "根目录外未创建 evil.txt" || bad "根目录外未创建 evil.txt"
[ ! -e "$DATA/evil.txt" ] && ok "数据目录内无 evil.txt" || bad "数据目录内无 evil.txt"

# ============ 校验和强制 ============
nosum=$(curl -s -o /dev/null -w '%{http_code}' -X PUT --data-binary @"$CLIENT/hello.md" "$BASE/api/put?path=nosum.txt")
check "缺少校验和被拒绝" "$nosum" "400"
BADSUM=$(printf '0%.0s' $(seq 1 64))
bad1=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "X-Checksum-SHA256: $BADSUM" --data-binary @"$CLIENT/hello.md" "$BASE/api/put?path=badsum.txt")
check "校验和不匹配被拒绝" "$bad1" "400"
bad2=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "X-Checksum-SHA256: abc" --data-binary @"$CLIENT/hello.md" "$BASE/api/put?path=badsum.txt")
check "校验和格式非法被拒绝" "$bad2" "400"
[ ! -e "$DATA/badsum.txt" ] && [ ! -e "$DATA/nosum.txt" ] && ok "坏请求不落盘" || bad "坏请求不落盘"

# ============ 409 / overwrite ============
conf=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Expect: 100-continue" -H "X-Checksum-SHA256: $SUM" --data-binary @"$CLIENT/hello.md" "$BASE/api/put?path=docs%2Fhello.md")
check "已存在返回 409" "$conf" "409"
ow=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "X-Checksum-SHA256: $SUM" --data-binary @"$CLIENT/hello.md" "$BASE/api/put?path=docs%2Fhello.md&overwrite=1")
check "overwrite=1 返回 201" "$ow" "201"

# ============ 传输中断 ============
before=$(fcount)
head -c 1048576 /dev/urandom > "$CLIENT/big.bin"
BIGSUM=$(hashof "$CLIENT/big.bin")
curl -s -o /dev/null --max-time 1 --limit-rate 40k -X PUT -H "X-Checksum-SHA256: $BIGSUM" \
  --data-binary @"$CLIENT/big.bin" "$BASE/api/put?path=big.bin" 2>/dev/null
crc=$?
check "curl 超时中断(退出码 28)" "$crc" "28"
sleep 1
[ ! -e "$DATA/big.bin" ] && ok "中断上传不产生最终文件" || bad "中断上传不产生最终文件"
check "中断上传无临时残留" "$(fcount)" "$before"
"$BIN" put --server "$BASE" "$CLIENT/hello.md" after-abort.md >/dev/null 2>&1
check "中断后服务仍正常" "$?" "0"

# ============ 默认 --data-dir ============
PORT2=$((PORT+1))
DEFW="$WORK/defaultcwd"; mkdir -p "$DEFW"
( cd "$DEFW" && exec "$BIN" serve --addr "127.0.0.1:$PORT2" ) >"$WORK/s2.log" 2>&1 &
S2_PID=$!
up2=0
for _ in $(seq 1 60); do
  curl -sf "http://127.0.0.1:$PORT2/api/ping" >/dev/null 2>&1 && { up2=1; break; }
  sleep 0.1
done
check "缺省参数服务端启动" "$up2" "1"
[ -d "$DEFW/data" ] && ok "缺省数据目录 ./data 自动创建" || bad "缺省数据目录 ./data 自动创建"
kill "$S2_PID" 2>/dev/null; S2_PID=""

# ============ 汇总 ============
echo
echo "----------"
echo "通过 $PASS, 失败 $FAIL"
if [ "$FAIL" -gt 0 ]; then
  echo "--- 服务端日志 ---"
  cat "$SRV_LOG" 2>/dev/null
  exit 1
fi
