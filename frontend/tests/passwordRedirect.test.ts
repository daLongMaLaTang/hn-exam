import { test } from 'node:test'
import assert from 'node:assert/strict'
import { api, ApiError } from '../src/api.ts'

test('password-required response never reloads account page but redirects other pages', async () => {
  const originalFetch = globalThis.fetch
  const descriptors = ['window', 'localStorage'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)] as const)
  const location = { pathname: '/account', href: '/account?from=login' }
  Object.defineProperty(globalThis, 'window', { configurable: true, value: { location } })
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => null } })
  globalThis.fetch = async () => new Response(JSON.stringify({ code: 'PASSWORD_CHANGE_REQUIRED', message: '请先修改密码' }), { status: 403 })
  try {
    for (let i = 0; i < 3; i++) await assert.rejects(api('/api/notifications'), e => e instanceof ApiError && e.code === 'PASSWORD_CHANGE_REQUIRED')
    assert.equal(location.href, '/account?from=login')
    location.pathname = '/'
    await assert.rejects(api('/api/notifications'), ApiError)
    assert.equal(location.href, '/account')
  } finally {
    globalThis.fetch = originalFetch
    for (const [key, descriptor] of descriptors) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else Reflect.deleteProperty(globalThis, key)
    }
  }
})
