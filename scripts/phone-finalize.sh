#!/data/data/com.termux/files/usr/bin/bash
set -u
cd "$HOME"
cp all.sh start-dsh.sh
chmod +x start-dsh.sh
mkdir -p "$HOME/.termux/boot"
cat > "$HOME/.termux/boot/start-dsh.sh" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
# 开机自启（需装 Termux:Boot 并打开一次）
exec bash "$HOME/all.sh"
EOS
chmod +x "$HOME/.termux/boot/start-dsh.sh"
echo "=== files"
ls -l start-dsh.sh all.sh redirect.js .termux/boot/start-dsh.sh
echo "=== token"
cat dsh-token.txt; echo
echo "=== DONE-FINAL"
