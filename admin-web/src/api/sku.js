import request from './request'

const BASE = '/skus'

export const listSkus = (params) => request.get(BASE, { params })
export const getSku = (id) => request.get(`${BASE}/${id}`)
export const createSku = (data) => request.post(BASE, data)
export const updateSku = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteSku = (id) => request.delete(`${BASE}/${id}`)
