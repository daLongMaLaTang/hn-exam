export function decodeBlankAnswers(value?: string | null): string[] {
  if (!value) return []
  try {
    const parsed: unknown = JSON.parse(value)
    if (Array.isArray(parsed) && parsed.every(item => typeof item === 'string')) return parsed
  } catch { /* Restore existing answers that used separators before separate inputs were added. */ }
  const legacy = value.replace(/[\r\n]+$/, '')
  return legacy.split(/[|｜\r\n]/.test(legacy) ? /[|｜]|\r\n|[\r\n]/ : /[,，、;；]/)
}

export function encodeBlankAnswers(values: string[]): string {
  return values.some(value => value.trim()) ? JSON.stringify(values) : ''
}

export function fillBlankCount(count: number | undefined, stem = '', savedAnswer = ''): number {
  if (count != null && Number.isInteger(count) && count > 0) return count
  // Compatibility while a previously opened page still has the older API response.
  const markers = stem.match(/[（(][\s_＿]*[）)]|[_＿]{2,}/g)
  return Math.max(markers?.length || 0, decodeBlankAnswers(savedAnswer).length, 1)
}

export function displayAnswer(type: string, value?: string | null): string {
  if (!value) return '未作答'
  if (type !== 'FILL_BLANK') return value
  return decodeBlankAnswers(value).map((answer, index) => `第 ${index + 1} 空：${answer.trim() || '未作答'}`).join('\n')
}
