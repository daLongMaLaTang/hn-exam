export type Question = {
  id: number
  type: string
  stem: string
  optionsJson?: string | null
  courseCode?: string
  chapter?: string
  difficulty?: string
  points?: number
  position?: number
  savedAnswer?: string | null
  blankCount?: number
}

export type Exam = {
  id: number
  title: string
  courseCode?: string | null
  description?: string
  durationMinutes: number
  totalScore: number
  passScore: number
  startTime?: string | null
  endTime?: string | null
  status: string
  allowRetake: boolean
  maxAttempts: number
  questionCount: number
  chapters?: string[]
  basicAntiCheat?: boolean
  cancellationReason?: string
  wrongQuestionPaper?: boolean
}

export type Attempt = {
  attemptId: number
  examId: number
  examTitle: string
  status: string
  startedAt: string
  deadline: string
  submissionDeadline: string
  answerRevision: number
  serverTime?: string
  focusLossCount?: number
  basicAntiCheat?: boolean
  questions: Question[]
}

export type Grade = {
  attemptId: number
  examId: number
  examTitle: string
  status: string
  startedAt: string
  submittedAt?: string | null
  score?: number | null
  totalScore: number
  passed: boolean
  focusLossCount: number
  subjectiveQuestionCount?: number
  pendingSubjectiveCount?: number
  gradedAt?: string | null
  gradedByEmployeeNo?: string | null
  gradingNote?: string | null
}

export const questionTypeLabels: Record<string, string> = {
  SINGLE_CHOICE: '单选题', MULTIPLE_CHOICE: '多选题', TRUE_FALSE: '判断题',
  FILL_BLANK: '填空题', CALCULATION: '计算题', SHORT_ANSWER: '简答题', IMAGE_CASE: '案例题', ESSAY: '大题'
}

export function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '不限'
}

export function errorText(error: unknown) {
  return error instanceof Error ? error.message : '操作未完成，请重试'
}
