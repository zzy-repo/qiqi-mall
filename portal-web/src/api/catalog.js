import request from './request'

export const listCategories = () => request.get('/categories')
export const listProducts = (params) => request.get('/products', { params })
export const getProductDetail = (id) => request.get(`/products/${id}`)
