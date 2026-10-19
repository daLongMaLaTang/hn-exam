import http from 'node:http';
import { createReadStream } from 'node:fs';
import { stat, realpath } from 'node:fs/promises';
import path from 'node:path';

// Serve only compiled files. Source code, environment files and Vite's dev server
// are never exposed through the public tunnel.
const root = await realpath(process.env.HNEXAM_FRONTEND_DIST);
const port = Number(process.env.HNEXAM_FRONTEND_PORT || 5173);
const publicUrl = process.env.HNEXAM_PUBLIC_URL || '';
const allowedHosts = new Set(['127.0.0.1', 'localhost']);
if (publicUrl) allowedHosts.add(new URL(publicUrl).hostname);
const mime = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg', '.ico': 'image/x-icon', '.webp': 'image/webp',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.pdf': 'application/pdf',
};
const hopHeaders = new Set(['connection', 'keep-alive', 'proxy-authenticate',
  'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade']);
function filteredHeaders(headers) {
  const blocked = new Set([...hopHeaders, ...(headers.connection || '').split(',').map(s => s.trim().toLowerCase())]);
  return Object.fromEntries(Object.entries(headers).filter(([key]) => !blocked.has(key.toLowerCase())));
}
function reply(res, code, message) {
  res.writeHead(code, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(message);
}
const server = http.createServer(async (req, res) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'SAMEORIGIN');
  res.setHeader('Referrer-Policy', 'same-origin');
  try {
    if (!req.headers.host || !allowedHosts.has(new URL(`http://${req.headers.host}`).hostname)) {
      return reply(res, 403, 'Host is not allowed');
    }
    // Reject traversal before URL normalization and never serve dotfiles.
    const rawPath = decodeURIComponent((req.url || '/').split('?')[0]);
    if (!rawPath.startsWith('/') || rawPath.includes('\\') || rawPath.includes('\0') ||
        rawPath.split('/').some(part => part.startsWith('.') || part.includes(':'))) {
      return reply(res, 404, 'Not found');
    }
    if (rawPath === '/api' || rawPath.startsWith('/api/')) {
      const headers = filteredHeaders(req.headers);
      headers.host = '127.0.0.1:8080';
      delete headers.forwarded;
      for (const name of Object.keys(headers)) if (name.startsWith('x-forwarded-')) delete headers[name];
      const upstream = http.request({ hostname: '127.0.0.1', port: 8080,
        method: req.method, path: req.url, headers }, backend => {
        res.writeHead(backend.statusCode || 502, { ...filteredHeaders(backend.headers), 'cache-control': 'no-store' });
        backend.on('error', () => res.destroy());
        backend.pipe(res);
      });
      upstream.setTimeout(120_000, () => upstream.destroy(new Error('Backend timeout')));
      upstream.on('error', () => {
        if (!res.headersSent) reply(res, 502, 'Backend is unavailable');
        else res.destroy();
      });
      req.on('aborted', () => upstream.destroy());
      res.on('close', () => { if (!res.writableEnded) upstream.destroy(); });
      req.pipe(upstream);
      return;
    }
    if (!['GET', 'HEAD'].includes(req.method)) return reply(res, 405, 'Method not allowed');
    let file = path.resolve(root, '.' + rawPath);
    if (file !== root && !file.startsWith(root + path.sep)) return reply(res, 404, 'Not found');
    let info;
    try { info = await stat(file); } catch { /* SPA routes use index.html below. */ }
    if (!info?.isFile()) {
      if (path.extname(rawPath) || rawPath.startsWith('/assets/')) return reply(res, 404, 'Not found');
      file = path.join(root, 'index.html');
      info = await stat(file);
    }
    const actual = await realpath(file);
    if (!actual.startsWith(root + path.sep)) return reply(res, 404, 'Not found');
    res.writeHead(200, { 'Content-Type': mime[path.extname(file)] || 'application/octet-stream',
      'Content-Length': info.size,
      'Cache-Control': rawPath.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'no-cache' });
    if (req.method === 'HEAD') return res.end();
    const stream = createReadStream(actual);
    stream.on('error', () => res.destroy());
    stream.pipe(res);
  } catch {
    if (!res.headersSent) reply(res, 400, 'Invalid request');
    else res.destroy();
  }
});
server.listen(port, '127.0.0.1', () => console.log(`Built frontend listening at http://127.0.0.1:${port}`));
server.on('error', err => { console.error(err.message); process.exit(1); });
