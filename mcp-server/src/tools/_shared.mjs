// 工具层公共辅助：文本包装 / 金额展示 / 分页夹取 / 错误包装。
// 所有域文件（order.mjs、catalog.mjs、以后新增的域）都从这里取，别各写一份。

/**
 * 工具返回值包装：字符串原样输出，对象自动 JSON.stringify。
 * ⚠️ 摘要 + JSON 的写法：先给人/模型看的摘要，再跟一段「JSON:」，
 *    便于模型后续精确处理（测试脚本也是靠 "JSON:" 标记切分的）。
 */
export function text(payload) {
  return { content: [{ type: 'text', text: typeof payload === 'string' ? payload : JSON.stringify(payload, null, 2) }] };
}

export function money(n) {
  return n === null || n === undefined ? '-' : String(n);
}

/** 后端/校验错误统一转成「模型能看懂的一句话」，不抛给协议层（后端中文 message 原样透出） */
export function fail(err) {
  console.error('[mcp] tool failed:', err?.message || err);
  return { isError: true, content: [{ type: 'text', text: `❌ ${err?.message || String(err)}` }] };
}

/**
 * 写工具错误包装（乐观锁专用）—— 比 fail() 多两件事：
 *   ① **保留后端 bizCode**：文本里显式带 `[409]` / `[400]`，测试与模型都能据此断言/分支；
 *   ② 409（版本冲突）给一段「为什么 + 该怎么做」：说清「你手上的 version 过期了」，
 *      并要求**重新读最新 version 再让用户确认**，明确禁止拿旧 version 反复重试。
 *      （后端是 fail-closed：UPDATE ... WHERE version=? 影响 0 行，一个字段都不会覆盖，
 *        所以旧 version 重试一万次也还是 409 —— 必须让模型知道「重试无用、要重读」。）
 *
 * @param {Error & {code?: number}} err  mall.request() 抛的 MallError（code = 后端 bizCode）
 * @param {{action?: string, label?: string, oldVersion?: number, rereadTool?: string}} opts
 */
export function failWrite(err, { action = '修改', label = '', oldVersion, rereadTool = '对应只读工具' } = {}) {
  const code = err?.code;
  const tag = code === undefined || code === null || code === 0 ? '' : `[${code}] `;
  const message = String(err?.message ?? err);
  console.error(`[mcp] write failed (${action} ${label}): ${tag}${message}`);

  const lines = [`❌ ${action}失败：${tag}${message}`];
  if (label) lines.push(`对象：${label}`);

  if (code === 409) {
    lines.push(`原因：你手上的 version=${oldVersion ?? '(未知)'} 已经过期，说明期间有人改过这条数据（你读完它之后又被写了一次）。`);
    lines.push(
      `下一步：请重新调用 ${rereadTool} 拿最新 version，把最新数据和这次要改的内容一起念给用户确认后再重试。` +
        '不要重复使用旧 version —— 后端不会覆盖被抢改的数据，旧 version 重试多少次都还是这个 409。'
    );
  } else if (code === 400) {
    lines.push('原因：请求里没有可用的版本号（后端 fail-closed，缺 version 一律拒绝，绝不静默降级成后写覆盖）。');
    lines.push(`下一步：请先调用 ${rereadTool} 拿到 version，带上它再提交。`);
  } else {
    lines.push(`下一步：按上面的原因修正参数后重试；若提示数据不存在，请先用只读工具确认 id。`);
  }

  return { isError: true, content: [{ type: 'text', text: lines.join('\n') }] };
}

// ---------- 分页 ----------
// 后端的分页夹取规则：page 最小 1，size 默认 10、最大 50。
// 本文件是 MCP 侧的统一入口——即使后端某接口不分页（如 /admin/products 全量返回），
// 也一律在工具层按同一套规则夹取，保证 10 批工具的分页语义完全一致。

export const DEFAULT_PAGE_SIZE = 10;
export const MAX_PAGE_SIZE = 50;

/** 夹取 page/size → { page, size }（page>=1，1<=size<=50） */
export function clampPage({ page, size } = {}) {
  let p = Number.isFinite(page) ? Math.trunc(page) : 1;
  let s = Number.isFinite(size) ? Math.trunc(size) : DEFAULT_PAGE_SIZE;
  if (p < 1) p = 1;
  if (s < 1) s = 1;
  if (s > MAX_PAGE_SIZE) s = MAX_PAGE_SIZE;
  return { page: p, size: s };
}

/** 内存分页：list → { records, total, page, size, pages }（与 MyBatis Page 字段同名，模型不用记两套） */
export function paginate(list, { page = 1, size = DEFAULT_PAGE_SIZE } = {}) {
  const all = Array.isArray(list) ? list : [];
  const total = all.length;
  const pages = total === 0 ? 0 : Math.ceil(total / size);
  const start = (page - 1) * size;
  return { records: all.slice(start, start + size), total, page, size, pages };
}

/** 分页头：统一的一句话，摘要里直接用 */
export function pageHeader(label, { total, page, size, pages }) {
  return pages === 0 ? `${label}共 0 条` : `${label}共 ${total} 条（第 ${page}/${pages} 页，每页 ${size} 条）`;
}
