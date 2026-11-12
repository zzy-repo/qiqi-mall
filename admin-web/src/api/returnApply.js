import request from './request'

const BASE = '/return-applies'

export const listReturnApplies = () => request.get(BASE)

/** 新增退货申请（非高危：只是建单，不影响订单/库存） */
export const createReturnApply = (data) => request.post(BASE, data)

/**
 * 审核退货申请（L3 高危）：status 1通过 / 2拒绝。
 * body = { ...原参数(status/description/...), confirmCode }。
 * 通过会退款 + 回补库存，拒绝会让订单回到已付款 —— 所以必须带码。
 */
export const updateReturnApply = (id, body) => request.put(`${BASE}/${id}`, body)

/**
 * 审核退货预演（?dryRun=true）：body 仍是「原参数」（后端要先看 status 才知道要预演通过还是拒绝）。
 * 返回 { willDo, confirmCode, expiresInSec }，库里零变化。
 */
export const updateReturnApplyDryRun = (id, body) =>
  request.put(`${BASE}/${id}`, body, { params: { dryRun: true } })
