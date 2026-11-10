import request from './request'

/**
 * 后台图片上传（见 docs/flows/9-图片上传时序图）
 * 走 multipart，返回 {url, objectName}
 */
export const uploadImage = (file) => {
  const form = new FormData()
  form.append('file', file)
  return request.post('/upload/image', form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
