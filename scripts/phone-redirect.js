// 本机入口：把固定地址 127.0.0.1:3081 跳到当前 dsh web 的带 token 地址
const fs = require('fs');
const http = require('http');
const TOKEN_FILE = process.env.HOME + '/dsh-token.txt';
const PORT = Number(process.env.TSUGU_PORT || 3081);

function readToken() {
  try {
    return fs.readFileSync(TOKEN_FILE, 'utf8').trim();
  } catch (e) {
    return '';
  }
}

http.createServer((req, res) => {
  const token = readToken();
  if (!token) {
    res.writeHead(503, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' });
    res.end('dsh web 还在启动，几秒后刷新一下\n');
    return;
  }
  res.writeHead(302, {
    location: 'http://127.0.0.1:3080/?token=' + encodeURIComponent(token),
    'cache-control': 'no-store',
  });
  res.end();
}).listen(PORT, '127.0.0.1', () => {
  console.log('local entry: http://127.0.0.1:' + PORT + '/');
});
