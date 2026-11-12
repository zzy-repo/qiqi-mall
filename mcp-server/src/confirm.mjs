// confirm_token：一次性、5 分钟、绑定用途 + 拆单方案指纹。
// 纯内存 Map（进程重启即失效，这是有意的：stdio server 生命周期 = 会话）。

import crypto from 'node:crypto';

export const TTL_MS = 5 * 60 * 1000;

/** token -> { tool, orderId, planHash, exp } */
const store = new Map();

/** 方案指纹：同一份方案必须算出同一个 hash；方案变了 hash 就变 */
export function hashPlan(plan) {
  return crypto.createHash('sha256').update(JSON.stringify(plan)).digest('hex');
}

/** 签发：preview_split 时调用 */
export function issue({ tool, orderId, plan }) {
  const planHash = hashPlan(plan);
  const token = crypto.randomUUID();
  store.set(token, { tool, orderId: Number(orderId), planHash, exp: Date.now() + TTL_MS });
  return { token, planHash };
}

/**
 * 消费：execute 时调用，四道校验，**无论成败都 delete**（一次性）。
 * ① 存在 ② 未过期 ③ 用途一致 ④ 一次性
 * 返回 { orderId, planHash }
 */
export function consume(token, expectedTool) {
  if (typeof token !== 'string' || !token) {
    throw new Error('确认令牌无效或已被使用，请重新预览方案');
  }
  const record = store.get(token);
  // ④ 一次性：先删再校验，失败也留不下可重试的令牌
  store.delete(token);

  // ① 存在
  if (!record) throw new Error('确认令牌无效或已被使用，请重新预览方案');
  // ② 未过期
  if (Date.now() > record.exp) throw new Error('确认令牌已过期（5 分钟），请重新预览方案');
  // ③ 用途一致
  if (record.tool !== expectedTool) {
    throw new Error(`确认令牌用途不匹配（该令牌用于 ${record.tool}，不能用于 ${expectedTool}），请重新预览方案`);
  }
  return { orderId: record.orderId, planHash: record.planHash };
}

/** 仅供测试/诊断 */
export function _size() {
  return store.size;
}
