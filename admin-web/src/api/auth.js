import request from './request'

/** 是否需要验证码（该用户名失败 ≥5 次） */
export const captchaRequired = (username) =>
  request.get('/auth/captcha-required', { params: { username } })

/** 获取图形验证码 {captchaId, image} */
export const getCaptcha = () => request.get('/auth/captcha')

/** 后台登录 */
export const login = (data) => request.post('/auth/login', data)
