import request from './request'

export const getHealth = () => request.get('/health')
