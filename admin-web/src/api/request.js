import axios from 'axios'
import { getToken, clearAuth } from '../utils/auth'

const request = axios.create({
  // 管理后台接口统一前缀 /admin
  baseURL: import.meta.env.VITE_API_BASE || '/admin',
  timeout: 10000
})

request.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  (res) => {
    const body = res.data
    // 统一解包后端 Result<T>
    if (body && typeof body.code === 'number') {
      if (body.code !== 0) {
        return Promise.reject(new Error(body.message || '请求失败'))
      }
      return body.data
    }
    return body
  },
  (err) => {
    const status = err.response?.status
    const message = err.response?.data?.message || err.message
    if (status === 401) {
      // 未登录/过期：清空并回登录页（带 redirect）
      const current = window.location.pathname + window.location.search
      clearAuth()
      if (!current.startsWith('/login')) {
        window.location.replace(`/login?redirect=${encodeURIComponent(current)}`)
      }
    }
    return Promise.reject(new Error(message || '请求失败'))
  }
)

export default request
