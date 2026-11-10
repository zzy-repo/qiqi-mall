import request from './request'

export const listReturnReasons = () => request.get('/returns/reasons')
export const listMyReturns = () => request.get('/returns')
export const applyReturn = (data) => request.post('/returns', data)
