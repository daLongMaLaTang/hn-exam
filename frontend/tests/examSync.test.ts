import { test } from 'node:test'
import assert from 'node:assert/strict'
import { sameAnswerSnapshot, draftNeedsReview } from '../src/candidates/examSync.ts'

test('a stale or legacy draft cannot silently replace a newer server answer', () => {
  assert.equal(draftNeedsReview({1:'旧答案'}, 0, {1:'新答案'}, 1), true)
  assert.equal(draftNeedsReview({1:'旧答案'}, undefined, {1:'新答案'}, 0), true)
  assert.equal(draftNeedsReview({1:'本地编辑'}, 1, {1:'已保存'}, 1), false)
})
test('response loss can be reconciled by comparing exact answers, including blank and whitespace', () => {
  assert.equal(draftNeedsReview({1:'收到的答案'}, 0, {1:'收到的答案'}, 1), false)
  assert.equal(sameAnswerSnapshot({1:'A'}, {1:'A',2:''}), true)
  assert.equal(sameAnswerSnapshot({1:'A '}, {1:'A'}), false)
})
