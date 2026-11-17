#!/data/data/com.termux/files/usr/bin/bash
# 一键：启动 dsh web（若没在跑）+ 固定入口 127.0.0.1:3081
set -u
cd "$HOME"
termux-wake-lock 2>/dev/null

echo "=== 1) 检查 redirect.js"
# redirect.js 在本仓库 scripts/phone-redirect.js，复制到手机的 $HOME 即可：
#   curl -fsS -o ~/redirect.js http://<你电脑的地址>:8898/scripts/phone-redirect.js
if [ ! -f redirect.js ]; then
  echo "缺少 redirect.js：请把仓库里的 scripts/phone-redirect.js 放到 $HOME/redirect.js" >&2
  exit 1
fi

echo "=== 2) 检查 3080 是否已在跑"
if grep -q '0100007F:0C08' /proc/net/tcp; then
  echo "3080 已在监听，复用"
else
  echo "启动 dsh web ..."
  rm -f dsh.log
  nohup proot-distro login ubuntu -- /usr/local/bin/dsh web --no-open --port 3080 > dsh.log 2>&1 &
fi

echo "=== 3) 等 token"
tok=""
i=0
while [ $i -lt 90 ]; do
  tok=$(grep -o 'token=[A-Za-z0-9_-]*' dsh.log 2>/dev/null | head -1 | cut -d= -f2)
  [ -n "$tok" ] && break
  i=$((i + 1))
  sleep 1
done
echo "$tok" > dsh-token.txt
echo "token 长度=${#tok}"

echo "=== 4) 启动固定入口"
pkill -f redirect.js 2>/dev/null
nohup node redirect.js > redirect.log 2>&1 &
sleep 2
cat redirect.log

echo "=== 5) 自检"
curl -s -o /dev/null -w 'entry_code=%{http_code}\n' http://127.0.0.1:3081/
curl -s -o /dev/null -w 'dsh_code=%{http_code}\n' "http://127.0.0.1:3080/?token=$(cat dsh-token.txt)"
echo "=== DONE-ALL"
