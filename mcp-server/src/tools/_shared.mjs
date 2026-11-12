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
