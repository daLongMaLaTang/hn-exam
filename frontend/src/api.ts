export type LoginResult = {
  accessToken: string
  expiresAt: string
  userId: number
  employeeNo: string
  name: string
  roles: string[]
  mustChangePassword: boolean
}

const TOKEN_KEY = 'hn_exam_token'
const USER_KEY = 'hn_exam_user'

// randomUUID is unavailable on ordinary HTTP LAN addresses used by phones.
export function newRequestId() {
  return typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : Array.from(crypto.getRandomValues(new Uint8Array(16)), value => value.toString(16).padStart(2, '0')).join('')
}

export function currentUser(): LoginResult | null {
  const raw = localStorage.getItem(USER_KEY)
  try { return raw ? JSON.parse(raw) as LoginResult : null } catch { return null }
}

export function hasRole(...roles: string[]) { return currentUser()?.roles.some(r => roles.includes(r)) ?? false }
export function saveUser(user: LoginResult) { localStorage.setItem(TOKEN_KEY, user.accessToken); localStorage.setItem(USER_KEY, JSON.stringify(user)) }
export async function refreshUser() {
  const previous = currentUser()
  if (!previous) return null
  const data = await api<Partial<LoginResult>>('/api/auth/me')
  const user = {...previous, ...data}
  saveUser(user)
  return user
}

export function isLoggedIn() {
  return Boolean(localStorage.getItem(TOKEN_KEY)) && new Date(currentUser()?.expiresAt || 0).getTime() > Date.now()
}

export function signOut() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export async function login(employeeNo: string, password: string) {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ employeeNo, password })
  })
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { message?: string }
    throw new Error(body.message || (response.status === 401 ? '工号或密码错误' : response.status === 403 ? '当前访问地址未获允许，请联系管理员检查访问配置' : `登录未完成（${response.status}），请稍后重试`))
  }
  const result = await response.json() as LoginResult
  localStorage.setItem(TOKEN_KEY, result.accessToken)
  localStorage.setItem(USER_KEY, JSON.stringify(result))
  return result
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = localStorage.getItem(TOKEN_KEY)
  const multipart = options.body instanceof FormData
  const response = await fetch(path, {
    ...options,
    headers: {
      ...(!multipart ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers
    }
  }).catch(() => { throw new Error('网络连接中断，请检查网络后重试') })
  if (response.status === 401) {
    signOut()
    window.location.href = '/login'
    throw new Error('登录已失效')
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { message?: string }
    if (response.status === 403 && (body as {code?: string}).code === 'PASSWORD_CHANGE_REQUIRED') window.location.href = '/account'
    throw new Error(body.message || `请求失败（${response.status}）`)
  }
  return response.json() as Promise<T>
}
