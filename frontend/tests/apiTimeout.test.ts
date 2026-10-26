import { test } from 'node:test'
import assert from 'node:assert/strict'
import { api, ApiError, RequestTimeoutError } from '../src/api.ts'

test('bounded requests release both stalled fetch and stalled response body, then allow retry', async () => {
  const originalFetch = globalThis.fetch
  const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => null } })
  try {
    let signal: AbortSignal | undefined
    globalThis.fetch = ((_url, options) => { signal = options?.signal as AbortSignal; return new Promise(() => {}) }) as typeof fetch
    await assert.rejects(api('/save', { timeoutMs: 20 }), RequestTimeoutError)
    assert.equal(signal?.aborted, true)
    globalThis.fetch = (async () => ({ok:true,status:200,json:() => new Promise(() => {})})) as typeof fetch
    await assert.rejects(api('/save', { timeoutMs: 20 }), RequestTimeoutError)
    globalThis.fetch = (async () => new Response(JSON.stringify({answerRevision:1}), {status:200})) as typeof fetch
    assert.deepEqual(await api('/save', { timeoutMs: 100 }), {answerRevision:1})
    globalThis.fetch = (async () => new Response(JSON.stringify({code:'ANSWER_CONFLICT',message:'另一页面已保存'}), {status:409})) as typeof fetch
    await assert.rejects(api('/save', { timeoutMs: 100 }), e => e instanceof ApiError && e.code === 'ANSWER_CONFLICT')
  } finally {
    globalThis.fetch = originalFetch
    if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage)
    else Reflect.deleteProperty(globalThis, 'localStorage')
  }
})
