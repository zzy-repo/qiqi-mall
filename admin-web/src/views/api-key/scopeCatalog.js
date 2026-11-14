/**
 * 令牌权限（scope）目录 —— **运行时从后端读**，这个文件里没有任何 scope 值。
 *
 * 唯一来源：`GET /admin/api-keys/scopes`（`ApiKeyAdminController#scopes`），它直接读
 * `AiApiKeyService.ALL_SCOPES`（13 个可授予 = 9 读 + 4 写）与 `ApiKeyAuthFilter.unGrantableWriteScopes()`
 * （5 个刻意不可授予的写 scope）。后端加一个 scope，页面当天就多一个勾选框。
 *
 * 为什么改成运行时读（这里是根治，不是修一次）：原先这份文件是后端两处的**忠实镜像**，
 * 注释里写着「改后端时必须同步改这里」。2026-11-13 后端给 MCP 补 `ledger:read / stock:read /
 * audit:read / channel:read` 时，镜像没跟上 —— 四个只读域在页面上勾不到，只能由接口直接写库。
 * 抄一遍必然走散，所以现在**没有抄本**：任何一次「前后端对标」都不再需要人工核对。
 *
 * 因此：**拉目录失败就报错并禁止保存**（见 `index.vue` 的 catalogState），绝不静默回落到
 * 一份写死的前端清单 —— 那种回落会把「后端少了接口/挂了」伪装成「一切正常」，比报错糟得多。
 *
 * 三个分组的**文案**（标题/提示）留在前端：它是界面措辞，不是目录内容；
 * 分到哪组、组里有哪几项、可不可勾，全部由接口的 read / write / unGrantable 决定。
 */
import { getScopeCatalog } from '../../api/apiKey'

/** 分组的展示层定义：只描述「怎么展示」，不含任何 scope 值 */
const GROUP_META = [
  {
    key: 'read',
    field: 'read',
    title: '只读',
    hint: '查询类接口，勾上不改变任何数据',
    grantable: true
  },
  {
    key: 'write',
    field: 'write',
    title: '可写',
    hint: '会改变数据，只给确实需要动手的令牌',
    grantable: true
  },
  {
    key: 'denied',
    field: 'unGrantable',
    title: '不可授予',
    hint: '这些写权限后端刻意没有放进权限目录：台账只追加、库存与审核决定订单命运、'
      + '拉单/回传发货是不可撤回的外呼、渠道回调重放会撞资金路径 —— 任何 AI 令牌都拿不到，只能人工 JWT 操作',
    grantable: false
  }
]

/** 目录结构自检：形态不对就当失败抛出去，别带着半个目录去渲染勾选框 */
const assertCatalog = (data) => {
  if (!data || typeof data !== 'object') throw new Error('权限目录返回空')
  for (const field of GROUP_META.map((g) => g.field)) {
    const list = data[field]
    if (!Array.isArray(list)) throw new Error(`权限目录字段 ${field} 不是数组`)
    for (const item of list) {
      if (!item || typeof item.value !== 'string' || !item.value) {
        throw new Error(`权限目录字段 ${field} 里有无效项`)
      }
    }
  }
  if (!Array.isArray(data.grantable) || !data.grantable.length) {
    throw new Error('权限目录里没有任何可授予权限')
  }
  return data
}

/** 拉目录（唯一入口）：网络失败 / 业务失败 / 形态不对，一律抛错，调用方据此禁用保存 */
export const fetchScopeCatalog = async () => assertCatalog(await getScopeCatalog())

/** 接口目录 → 页面分组（保留 testid 用的 key：read / write / denied） */
export const buildScopeGroups = (catalog) => {
  if (!catalog) return []
  return GROUP_META.map((meta) => ({
    key: meta.key,
    title: meta.title,
    hint: meta.hint,
    grantable: meta.grantable,
    scopes: (catalog[meta.field] || []).map((item) => ({ value: item.value, label: item.label || item.value }))
  }))
}

/** 保存白名单：接口原样回的 grantable（后端没授予的值，前端连提交都不会提交） */
export const grantableValuesOf = (catalog) => (catalog?.grantable || [])

/**
 * scope → 中文标签（列表列用）。
 * 目录里没有的值原样显示 —— 那是「后端删了/改了 scope，库里还留着旧值」的现场，
 * 显示生值能一眼看出来，填空字符串只会让人以为权限丢了。
 */
export const scopeLabelsOf = (catalog) => {
  const map = {}
  if (catalog) {
    for (const meta of GROUP_META) {
      for (const item of catalog[meta.field] || []) map[item.value] = item.label || item.value
    }
  }
  return (scope) => map[scope] || scope
}
