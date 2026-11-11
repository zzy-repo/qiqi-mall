/**
 * 环境与通用小工具：目标地址、唯一后缀。
 *
 * 单独成文件是为了让 ui.js（真实 UI 操作）与 assert.js（只读断言）互不依赖，
 * 两者都只依赖这里；业务操作一律走 ui.js，断言读取一律走 assert.js。
 */
export const PORTAL = process.env.PORTAL_BASE || 'http://127.0.0.1:5173'
export const ADMIN = process.env.ADMIN_BASE || 'http://127.0.0.1:5175'
export const API = process.env.API_BASE || 'http://127.0.0.1:8081'

/** 每次运行唯一后缀，避免唯一键冲突（用户名等） */
export const stamp = () => Date.now().toString().slice(-8)
