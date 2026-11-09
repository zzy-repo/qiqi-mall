import request from './request'

const BASE = '/return-reasons'

export const listReturnReasons = () => request.get(BASE)
export const createReturnReason = (data) => request.post(BASE, data)
export const updateReturnReason = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteReturnReason = (id) => request.delete(`${BASE}/${id}`)
