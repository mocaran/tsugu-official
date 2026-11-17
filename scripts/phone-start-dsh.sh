#!/data/data/com.termux/files/usr/bin/bash
# 一键：重启 dsh web 并在 127.0.0.1:3081 提供固定入口（可重复执行，先清理旧实例）
set -u
export PATH=/data/data/com.termux/files/usr/bin:$PATH
cd "$HOME"
termux-wake-lock 2>/dev/null || true

echo "=== 1) 清理旧实例"
pkill -f 'dsh web' 2>/dev/null || true
pkill -f 'proot-distro login ubuntu' 2>/dev/null || true
pkill -f redirect.js 2>/dev/null || true
sleep 3

echo "=== 2) 启动 dsh web"
rm -f dsh.log
nohup proot-distro login ubuntu -- /usr/local/bin/dsh web --no-open --port 3080 > dsh.log 2>&1 &

echo "=== 3) 等 token"
tok=""
i=0
while [ $i -lt 120 ]; do
  tok=$(grep -o 'token=[A-Za-z0-9_-]*' dsh.log 2>/dev/null | head -1 | cut -d= -f2)
  [ -n "$tok" ] && break
  i=$((i + 1))
  sleep 1
done
if [ -z "$tok" ]; then
  echo "!! token 没出现，dsh.log 尾部："
  tail -15 dsh.log
else
  echo "token 长度=${#tok}"
fi
echo "$tok" > dsh-token.txt

echo "=== 4) 启动固定入口"
nohup node "$HOME/redirect.js" > redirect.log 2>&1 &
sleep 2

echo "=== 5) 自检"
curl -s -o /dev/null -w 'entry=%{http_code}\n' http://127.0.0.1:3081/
echo "=== DONE-ALL"
