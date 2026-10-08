import http from 'node:http';
import {readFile, stat} from 'node:fs/promises';
import {resolve, extname} from 'node:path';
const root = resolve('public');
const types = {'.html':'text/html', '.js':'text/javascript', '.css':'text/css', '.svg':'image/svg+xml', '.json':'application/json', '.woff2':'font/woff2'};
const port = Number(process.env.PORT || 8080);
http.createServer(async (req, res) => {
  try {
    const path = resolve(root, '.' + decodeURIComponent(new URL(req.url, 'http://localhost').pathname));
    if (!path.startsWith(root + '/') && path !== root) {res.writeHead(403).end(); return;}
    const file = (await stat(path)).isDirectory() ? resolve(path, 'index.html') : path;
    res.writeHead(200, {'Content-Type': types[extname(file)] || 'application/octet-stream'});
    res.end(await readFile(file));
  } catch { res.writeHead(404).end('Not found'); }
}).listen(port, () => console.log(`ndcalc → http://localhost:${port}`));
