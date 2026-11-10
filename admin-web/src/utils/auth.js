const TOKEN_KEY = 'qiqi_admin_token'
const USER_KEY = 'qiqi_admin_user'

export const getToken = () => localStorage.getItem(TOKEN_KEY) || ''
export const setToken = (token) => localStorage.setItem(TOKEN_KEY, token)
export const getUser = () => {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || '{}')
  } catch {
    return {}
  }
}
export const setUser = (user) => localStorage.setItem(USER_KEY, JSON.stringify(user || {}))
export const clearAuth = () => {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}
