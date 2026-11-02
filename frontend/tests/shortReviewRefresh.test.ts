import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { parse, compileScript } = require('@vue/compiler-sfc')
const ts = require('typescript')
const vue = require('vue')
function harness() {
  const source = readFileSync(new URL('../src/components/ShortAnswerReview.vue', import.meta.url), 'utf8')
  const script = compileScript(parse(source).descriptor, { id: 'review-test' }).content
  const code = ts.transpileModule(script, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const requests: any[] = []
  const exports: any = {}
  vm.runInNewContext(code, { exports, Error, require: (name: string) => name === '../api' ? { api: (url: string, options: any) => new Promise((resolve, reject) => requests.push({ url, options, resolve, reject })) } : { ...vue, onMounted() {}, onBeforeUnmount() {} }, setTimeout, clearTimeout })
  return { state: exports.default.setup({ examId: '1' }, { expose() {}, emit() {} }), requests }
}
const workspace = (accepted: number) => ({ rows: [], accepted, pending: 0, suggested: 0, reviewRequired: 0 })
test('background refresh keeps editing enabled and an old response cannot overwrite a saved result', async () => {
  const { state: s, requests: r } = harness()
  const background = s.load(false, true)
  assert.equal(s.loading.value, false)
  assert.equal(s.refreshing.value, true)
  const save = s.run('/manual', {}, 'saved')
  assert.equal(s.busy.value, true)
  await s.run('/manual', {}, 'duplicate')
  assert.equal(r.length, 3)
  r[2].resolve({})
  await new Promise(resolve => setImmediate(resolve))
  r[3].resolve(workspace(1)); r[4].resolve([])
  await save
  r[0].resolve(workspace(0)); r[1].resolve([])
  await background
  assert.equal(s.data.value.accepted, 1)
  assert.equal(s.busy.value, false)
  assert.equal(s.loading.value, false)
})
test('failed refresh releases state and failed save retains the draft and permits retry', async () => {
  const { state: s, requests: r } = harness()
  const refresh = s.load(false, true)
  assert.equal(r[0].options.timeoutMs, 15000)
  r[0].reject(new Error('timeout')); r[1].resolve([])
  await refresh
  assert.equal(s.refreshing.value, false)
  s.drafts.value.x = { points: 2, reason: '', revision: 1 }
  const save = s.run('/manual', {}, 'saved', 'x')
  assert.equal(r[2].options.timeoutMs, 30000)
  r[2].reject(new Error('save failed'))
  await new Promise(resolve => setImmediate(resolve))
  r[3].resolve(workspace(0)); r[4].resolve([])
  await save
  assert.equal(s.busy.value, false)
  assert.equal(s.loading.value, false)
  assert.equal(s.drafts.value.x.points, 2)
  assert.equal(s.error.value, 'save failed')
})

