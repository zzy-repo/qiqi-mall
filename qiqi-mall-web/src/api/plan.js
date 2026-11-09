import request from './request'

const BASE = '/plans'

export const listPlans = () => request.get(BASE)
export const createPlan = (data) => request.post(BASE, data)
export const updatePlan = (id, data) => request.put(`${BASE}/${id}`, data)
export const deletePlan = (id) => request.delete(`${BASE}/${id}`)
