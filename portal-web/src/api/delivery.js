import request from './request'

/** 买家查看自己订单的包裹与物流 */
export const listMyDeliveries = (orderId) => request.get('/deliveries', { params: { orderId } })
