import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isClipboardShortcut, isClipboardInput } from '../src/candidates/examClipboard.ts'
const key = (key: string, modifiers = {}) => ({key,ctrlKey:false,metaKey:false,shiftKey:false,altKey:false,isComposing:false,...modifiers})
test('blocks Windows, Mac and legacy clipboard shortcuts', () => {
  for (const k of ['c','C','x','v','Insert']) assert.equal(isClipboardShortcut(key(k,{ctrlKey:true})),true)
  for (const k of ['c','x','v']) assert.equal(isClipboardShortcut(key(k,{metaKey:true})),true)
  for (const k of ['Insert','Delete']) assert.equal(isClipboardShortcut(key(k,{shiftKey:true})),true)
})
test('keeps typing, selection, navigation, undo and IME usable', () => {
  for (const k of ['c','x','v','Backspace','Delete','ArrowLeft']) assert.equal(isClipboardShortcut(key(k)),false)
  for (const k of ['a','z','y','ArrowLeft']) assert.equal(isClipboardShortcut(key(k,{ctrlKey:true})),false)
  assert.equal(isClipboardShortcut(key('v',{ctrlKey:true,isComposing:true})),false)
  assert.equal(isClipboardShortcut(key('v',{ctrlKey:true,altKey:true})),false)
})
test('blocks clipboard and drag input while preserving typing and IME input', () => {
  for (const type of ['insertFromPaste','insertFromPasteAsQuotation','insertFromDrop','deleteByCut','deleteByDrag']) assert.equal(isClipboardInput(type),true)
  for (const type of ['insertText','insertCompositionText','deleteContentBackward','historyUndo']) assert.equal(isClipboardInput(type),false)
})
