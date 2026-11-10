import request from './request'

export const getProfile = () => request.get('/member/profile')
export const listAddresses = () => request.get('/member/addresses')
export const addAddress = (data) => request.post('/member/addresses', data)
export const updateAddress = (id, data) => request.put(`/member/addresses/${id}`, data)
export const deleteAddress = (id) => request.delete(`/member/addresses/${id}`)
