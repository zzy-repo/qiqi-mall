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

/**
 * 把后端业务 code 挂到 Error 上再 reject。
 *
 * 为什么不能只 reject 一个 new Error(message)：业务 code 是调用方唯一能区分
 * 「409 版本冲突（被别人改过，要刷新）」和「400 缺版本号（前端 bug）/ 其他失败」的依据。
 * 早期实现把 code 丢了，前端只能看到一句中文，除了无差别 toast 什么都做不了。
 */
const bizError = (message, bizCode) => {
  const err = new Error(message || '请求失败')
  err.bizCode = bizCode
  return err
}

request.interceptors.response.use(
  (res) => {
    const body = res.data
    // 统一解包后端 Result<T>
    if (body && typeof body.code === 'number') {
      if (body.code !== 0) {
        // 业务失败：code 原样保留（0/1/400/409…），交给 useConflictHandler 决定交互
        return Promise.reject(bizError(body.message, body.code))
      }
      return body.data
    }
    return body
  },
  (err) => {
    const status = err.response?.status
    const message = err.response?.data?.message || err.message
    // HTTP 层的 body 也可能带业务 code（网关/异常处理器直出的 400/409），一并保留
    const bizCode = err.response?.data?.code
    if (status === 401) {
      // 未登录/过期：清空并回登录页（带 redirect）
      const current = window.location.pathname + window.location.search
      clearAuth()
      if (!current.startsWith('/login')) {
        window.location.replace(`/login?redirect=${encodeURIComponent(current)}`)
      }
    }
    return Promise.reject(bizError(message, bizCode))
  }
)

export default request
