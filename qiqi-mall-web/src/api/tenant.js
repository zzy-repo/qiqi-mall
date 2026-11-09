import request from './request'

const BASE = '/tenants'

export const listTenants = (params) => request.get(BASE, { params })
export const getTenant = (id) => request.get(`${BASE}/${id}`)
export const getTenantDetail = (id) => request.get(`${BASE}/${id}/detail`)
export const createTenant = (data) => request.post(BASE, data)
export const updateTenant = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteTenant = (id) => request.delete(`${BASE}/${id}`)
