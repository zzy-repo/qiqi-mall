import request from './request'

const BASE = '/categories'

export const listCategories = () => request.get(BASE)
export const getCategory = (id) => request.get(`${BASE}/${id}`)
export const createCategory = (data) => request.post(BASE, data)
export const updateCategory = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteCategory = (id) => request.delete(`${BASE}/${id}`)
