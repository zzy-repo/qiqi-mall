import request from './request'

const BASE = '/members'

export const listMembers = (params) => request.get(BASE, { params })
export const getMember = (id) => request.get(`${BASE}/${id}`)
export const getMemberDetail = (id) => request.get(`${BASE}/${id}/detail`)
export const createMember = (data) => request.post(BASE, data)
export const updateMember = (id, data) => request.put(`${BASE}/${id}`, data)
export const deleteMember = (id) => request.delete(`${BASE}/${id}`)
