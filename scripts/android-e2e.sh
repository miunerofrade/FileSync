#!/usr/bin/env bash
# Android 集成探针：构建 Go 服务端 → 启动 → 运行 App 的 JVM 测试（同步逻辑单测 + 真实服务端集成）→ 停止
# 前提: JDK 用 Android Studio 自带 JBR，Gradle 位于 /d/programming/tools/gradle-9.2.0（或自行调整）
set -u
cd "$(dirname "$0")/.." || exit 1

GRADLE="${GRADLE:-/d/programming/tools/gradle-9.2.0/bin/gradle.bat}"
export JAVA_HOME="${JAVA_HOME:-D:\\android studio\\jbr}"

BIN=./filesync.exe
[ -f "$BIN" ] || BIN=./filesync
[ -f "$BIN" ] || { echo "构建 Go 服务端..."; /d/Go/bin/go build -o filesync.exe . || exit 1; BIN=./filesync.exe; }
BIN="$PWD/${BIN#./}"

PORT=18190
BASE="http://127.0.0.1:$PORT"
WORK=$(mktemp -d)
SRV_PID=""

cleanup() {
  [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null
  sleep 0.3
  rm -rf "$WORK" 2>/dev/null
  true
}
trap cleanup EXIT

"$BIN" serve --addr "127.0.0.1:$PORT" --data-dir "$WORK/data" >"$WORK/server.log" 2>&1 &
SRV_PID=$!
up=0
for _ in $(seq 1 60); do
  curl -sf "$BASE/api/ping" >/dev/null 2>&1 && { up=1; break; }
  sleep 0.2
done
if [ "$up" != "1" ]; then echo "FAIL 服务端未启动"; exit 1; fi
echo "服务端已启动: $BASE"

export FILESYNC_TEST_SERVER="$BASE"
cd android || exit 1
"$GRADLE" :app:testDebugUnitTest
rc=$?
cd ..

if [ $rc -eq 0 ]; then
  echo "----------"
  echo "Android 测试全部通过"
else
  echo "----------"
  echo "Android 测试失败，退出码 $rc"
  echo "--- 测试报告 ---"
  cat android/app/build/reports/tests/testDebugUnitTest/index.html 2>/dev/null | grep -oE '<pre>[^<]*</pre>' | head -40
  cat "$WORK/server.log"
fi
exit $rc
