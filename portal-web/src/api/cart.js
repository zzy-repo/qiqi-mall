import request from './request'

export const listCart = () => request.get('/cart')
export const addToCart = (data) => request.post('/cart', data)
export const updateCartQuantity = (id, quantity) => request.put(`/cart/${id}`, { quantity })
export const removeCartItem = (id) => request.delete(`/cart/${id}`)
export const mergeLocalCart = (items) => request.post('/cart/merge', items)
