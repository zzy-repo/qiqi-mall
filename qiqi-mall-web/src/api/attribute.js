import request from './request'

const BASE = '/attributes'

export const listAttributes = (params) => request.get(BASE, { params })
export const getAttribute = (id) => request.get(`${BASE}/${id}`)
export const createAttribute = (data) => request.post(BASE, data)
export const updateAttribute = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteAttribute = (id) => request.delete(`${BASE}/${id}`)
