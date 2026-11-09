import request from './request'

const BASE = '/order-setting'

export const getOrderSetting = () => request.get(BASE)
export const updateOrderSetting = (data) => request.put(BASE, data)
