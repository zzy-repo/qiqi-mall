import axios from 'axios'
import { getToken, clearAuth } from '../utils/auth'

const request = axios.create({
  // C 端接口统一前缀 /portal
  baseURL: import.meta.env.VITE_API_BASE || '/portal',
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
    if (body && typeof body.code === 'number') {
      if (body.code !== 0) return Promise.reject(new Error(body.message || '请求失败'))
      return body.data
    }
    return body
  },
  (err) => {
    const status = err.response?.status
    const message = err.response?.data?.message || err.message
    if (status === 401) {
      clearAuth()
      const current = window.location.pathname + window.location.search
      if (!current.startsWith('/login')) {
        window.location.replace(`/login?redirect=${encodeURIComponent(current)}`)
      }
    }
    return Promise.reject(new Error(message || '请求失败'))
  }
)

export default request
