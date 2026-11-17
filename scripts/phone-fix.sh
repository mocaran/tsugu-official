#!/data/data/com.termux/files/usr/bin/bash
# 手机本地 DSH 收尾脚本：补原生模块 + 生成启动脚本
# 用法：在 Termux 里 bash phone-fix.sh
set -u

echo "=== 1) Ubuntu 容器换清华源并装 build-essential"
proot-distro login ubuntu -- sed -i 's@//archive.ubuntu.com@//mirrors.tuna.tsinghua.edu.cn@g; s@//security.ubuntu.com@//mirrors.tuna.tsinghua.edu.cn@g' /etc/apt/sources.list.d/ubuntu.sources
proot-distro login ubuntu -- apt-get update -y
proot-distro login ubuntu -- apt-get install -y build-essential python3 pkg-config

echo "=== 2) 允许安装脚本后重装 dsh（node-pty / koffi 才会真正编译）"
ALLOW='@deepseek-ai/dsh-subprocess-local,koffi,node-pty,@google/genai,protobufjs'
proot-distro login ubuntu -- /usr/local/bin/npm config set "allow-scripts=$ALLOW" --location=user
proot-distro login ubuntu -- /usr/local/bin/npm i -g @deepseek-ai/dsh --no-fund --no-audit "--allow-scripts=$ALLOW"

echo "=== 3) 生成启动脚本 ~/start-dsh.sh"
cat > "$HOME/start-dsh.sh" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
# 启动手机本地的 DSH（Termux + Ubuntu 容器）
termux-wake-lock
exec proot-distro login ubuntu -- /usr/local/bin/dsh web --no-open --port 3080
EOS
chmod +x "$HOME/start-dsh.sh"

echo "=== 4) 自检"
proot-distro login ubuntu -- /usr/local/bin/node -e "try{require('node-pty');console.log('node-pty OK')}catch(e){console.log('node-pty FAIL: '+e.message)}"
echo "=== 完成。以后在 Termux 里执行 ~/start-dsh.sh 就能起服务。"
