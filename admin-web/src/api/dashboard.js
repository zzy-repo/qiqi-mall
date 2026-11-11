import request from './request'

/** 数据概览：一次拿到看板全部数字（后端 /admin/dashboard/overview） */
export const getDashboardOverview = () => request.get('/dashboard/overview')
