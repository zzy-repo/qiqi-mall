import request from './request'

const BASE = '/member-addresses'

export const listAddresses = (params) => request.get(BASE, { params })
export const createAddress = (data) => request.post(BASE, data)
export const updateAddress = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteAddress = (id) => request.delete(`${BASE}/${id}`)
