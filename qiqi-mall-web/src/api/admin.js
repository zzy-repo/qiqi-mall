import request from './request'

const BASE = '/admins'

export const listAdmins = (params) => request.get(BASE, { params })
export const createAdmin = (data) => request.post(BASE, data)
export const updateAdmin = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteAdmin = (id) => request.delete(`${BASE}/${id}`)
