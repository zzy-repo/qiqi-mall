import request from './request'

const BASE = '/warehouses'

export const listWarehouses = () => request.get(BASE)
export const createWarehouse = (data) => request.post(BASE, data)
export const updateWarehouse = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteWarehouse = (id) => request.delete(`${BASE}/${id}`)
