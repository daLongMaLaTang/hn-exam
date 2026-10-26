export type AnswerWrite = { answers: Record<string, string>; focusLossCount: number; expectedRevision: number; requestId: string }
export type PendingAnswerWrite = { kind: 'save' | 'submit'; body: AnswerWrite }
export function sameAnswerSnapshot(left: Record<string, string>, right: Record<string, string>) {
  return [...new Set([...Object.keys(left), ...Object.keys(right)])].every(key => (left[key] || '') === (right[key] || ''))
}
export function draftNeedsReview(local: Record<string, string>, baseRevision: number | undefined,
                                 server: Record<string, string>, serverRevision: number) {
  return baseRevision !== serverRevision && !sameAnswerSnapshot(local, server)
}
