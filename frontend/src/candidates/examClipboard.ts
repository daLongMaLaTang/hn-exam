export function isClipboardShortcut(event: Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'metaKey' | 'shiftKey' | 'altKey' | 'isComposing'>) {
  if (event.isComposing || event.altKey) return false
  const key = event.key.toLowerCase()
  return ((event.ctrlKey || event.metaKey) && ['c', 'x', 'v', 'insert'].includes(key))
    || (event.shiftKey && ['insert', 'delete'].includes(key))
}

export function isClipboardInput(inputType: string) {
  return ['insertFromPaste', 'insertFromPasteAsQuotation', 'insertFromDrop', 'deleteByCut', 'deleteByDrag'].includes(inputType)
}
