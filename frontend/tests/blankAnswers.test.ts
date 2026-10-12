import test from 'node:test'
import assert from 'node:assert/strict'
import { decodeBlankAnswers, encodeBlankAnswers, fillBlankCount, displayAnswer } from '../src/candidates/blankAnswers.ts'

test('separate inputs preserve an empty middle blank through saving and restoring', () => {
  const saved = encodeBlankAnswers(['人身', '', '设备'])
  assert.deepEqual(decodeBlankAnswers(saved), ['人身', '', '设备'])
  assert.equal(displayAnswer('FILL_BLANK', saved), '第 1 空：人身\n第 2 空：未作答\n第 3 空：设备')
})
test('punctuation within an input remains part of that answer', () => {
  assert.deepEqual(decodeBlankAnswers(encodeBlankAnswers(['甲,乙；丙|丁', '"quoted"'])), ['甲,乙；丙|丁', '"quoted"'])
})
test('legacy separator and newline drafts preserve missing positions', () => {
  assert.deepEqual(decodeBlankAnswers('人身；；设备'), ['人身', '', '设备'])
  assert.deepEqual(decodeBlankAnswers('\n电网\n设备'), ['', '电网', '设备'])
  assert.deepEqual(decodeBlankAnswers('人身\r\n\r\n设备\r\n'), ['人身', '', '设备'])
})
test('clearing every input leaves the question unanswered', () => {
  assert.equal(encodeBlankAnswers(['', ' ', '']), '')
})
test('backend count wins and older responses can use the visible stem', () => {
  assert.equal(fillBlankCount(3, '( )', 'one'), 3)
  assert.equal(fillBlankCount(undefined, '保证（ ）、（ ）和（ ）安全。'), 3)
  assert.equal(fillBlankCount(undefined, '___ 与 ___'), 2)
  assert.equal(fillBlankCount(undefined, '没有标记', '甲；乙；丙'), 3)
  assert.equal(fillBlankCount(undefined), 1)
})
test('non-fill answers keep their original display', () => {
  assert.equal(displayAnswer('MULTIPLE_CHOICE', '甲,乙'), '甲,乙')
  assert.equal(displayAnswer('FILL_BLANK', ''), '未作答')
})
