import assert from 'node:assert/strict';
import http from 'node:http';

// Read-only smoke checks against the local built frontend and authenticated API.
const base = 'http://127.0.0.1:5173';
const page = await fetch(base);
assert.equal(page.status, 200);
const html = await page.text();
const asset = html.match(/src="(\/assets\/[^\"]+\.js)"/)?.[1];
assert.ok(asset, 'Compiled JavaScript entry must be present');
assert.equal((await fetch(base + asset)).status, 200);
assert.match((await fetch(base + asset)).headers.get('content-type'), /javascript/);
assert.equal((await fetch(base + '/dashboard')).status, 200, 'SPA deep links must work');
const health = await (await fetch(base + '/api/health')).json();
assert.equal(health.status, 'UP');
assert.equal(health.redis, 'UP');
const protectedResponse = await fetch(base + '/api/users');
assert.equal(protectedResponse.status, 401, 'Protected API must require authentication');
for (const resource of ['/.env.local', '/src/App.vue', '/deploy-cloudflare.ps1', '/assets/missing.js']) {
  assert.equal((await fetch(base + resource)).status, 404, resource);
}
assert.equal((await fetch(base + '/', { method: 'POST' })).status, 405);
function rawRequest(requestPath, host = '127.0.0.1:5173') {
  return new Promise((resolve, reject) => {
    const req = http.get({ hostname: '127.0.0.1', port: 5173, path: requestPath, headers: { host } }, res => {
      res.resume(); res.on('end', () => resolve(res.statusCode));
    });
    req.on('error', reject);
  });
}
assert.equal(await rawRequest('/%2e%2e/.env.local'), 404);
assert.equal(await rawRequest('/%5c..%5c.env.local'), 404);
assert.equal(await rawRequest('/%'), 400);
assert.equal(await rawRequest('/', 'untrusted.example'), 403);
const preflight = await fetch(base + '/api/auth/login', { method: 'OPTIONS', headers: {
  Origin: base, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'content-type',
} });
assert.equal(preflight.status, 200);
assert.equal(preflight.headers.get('access-control-allow-origin'), base);
const untrusted = await fetch(base + '/api/auth/login', { method: 'OPTIONS', headers: {
  Origin: 'https://untrusted.example', 'Access-Control-Request-Method': 'POST',
} });
assert.equal(untrusted.status, 403);
console.log('PASS: built page/assets, SPA routing, MySQL-backed API, Redis, authentication, CORS, private-file/traversal/host guards.');
