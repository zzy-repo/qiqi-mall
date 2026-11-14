/**
 * 令牌权限（scope）目录 —— 页面上渲染几个勾选框，唯一的事实来源是后端。
 *
 * 这份文件是后端两处的**忠实镜像**，改后端时必须同步改这里：
 *   1. 可授予：`qiqi-mall-server/src/main/java/com/qiqi/mall/apikey/service/AiApiKeyService.java`
 *      的 `ALL_SCOPES`（**13 个**，其中 9 个读 + 4 个写）；
 *   2. 不可授予：`qiqi-mall-server/src/main/java/com/qiqi/mall/shared/security/ApiKeyAuthFilter.java`
 *      的 `unGrantableWriteScopes()`（**5 个**写 scope）。
 *
 * 为什么不能只写死「界面上常用的那几项」：`ALL_SCOPES` 里每多一个 scope，就多一层
 * 「AI 能做什么」；页面少渲染一个，那个能力就只能由接口直接写入 —— 人反而被自己的界面
 * 挡在门外（2026-11-13 给 MCP 补的台账/库存/审核/渠道四个只读域，就是这么被漏掉整整一天的）。
 *
 * 三个分组的口径：
 *   · 读：纯查询，勾上不改变任何数据；
 *   · 写：可授予的写权（AI 建单、发货、改商品），勾上就等于把「改」的能力交出去；
 *   · 不可授予：后端**结构上**不放进 `ALL_SCOPES`，任何令牌都拿不到（保存时后端会回
 *     「未知权限」），所以在这里渲染成 disabled + 说明为什么 —— 让人看得见「这把钥匙不存在」，
 *     而不是让人以为页面漏了功能。理由见 ApiKeyAuthFilter 里 NO_WRITE_* 各常量的注释。
 */

/** 分组：grantable=false 的组只做展示，永远不可勾选 */
export const SCOPE_GROUPS = [
  {
    key: 'read',
    title: '只读',
    hint: '查询类接口，勾上不改变任何数据',
    grantable: true,
    scopes: [
      { value: 'order:read', label: '订单-读' },
      { value: 'catalog:read', label: '商品-读' },
      { value: 'member:read', label: '会员-读' },
      { value: 'config:read', label: '配置-读' },
      { value: 'dashboard:read', label: '概览-读' },
      // 以下四个是 2026-11-13 为 MCP 排障与巡检补的只读域
      { value: 'ledger:read', label: '台账-读' },
      { value: 'stock:read', label: '库存-读' },
      { value: 'audit:read', label: '审核-读' },
      { value: 'channel:read', label: '渠道-读' }
    ]
  },
  {
    key: 'write',
    title: '可写',
    hint: '会改变数据，只给确实需要动手的令牌',
    grantable: true,
    scopes: [
      { value: 'order:write', label: '订单-写' },
      { value: 'catalog:write', label: '商品-写' },
      { value: 'member:write', label: '会员-写' },
      { value: 'config:write', label: '配置-写' }
    ]
  },
  {
    key: 'denied',
    title: '不可授予',
    hint: '这些写权限后端刻意没有放进权限目录：台账只追加、库存与审核决定订单命运、'
      + '拉单/回传发货是不可撤回的外呼、渠道回调重放会撞资金路径 —— 任何 AI 令牌都拿不到，只能人工 JWT 操作',
    grantable: false,
    scopes: [
      { value: 'ledger:write', label: '台账-写' },
      { value: 'stock:write', label: '库存-写' },
      { value: 'audit:write', label: '审核-写' },
      { value: 'channel:write', label: '渠道-写' },
      { value: 'payment:write', label: '支付-写' }
    ]
  }
]

/** 可勾选的那些（后端 ALL_SCOPES 的全部内容，13 个） */
export const GRANTABLE_SCOPES = SCOPE_GROUPS
  .filter((group) => group.grantable)
  .flatMap((group) => group.scopes)

/** 保存时允许提交的 scope 白名单（顺序即页面顺序） */
export const GRANTABLE_SCOPE_VALUES = GRANTABLE_SCOPES.map((scope) => scope.value)

/** 列表里展示的中文标签：后端若新增了前端还不认识的 scope，就原样显示，不显示空白 */
export const SCOPE_LABEL = SCOPE_GROUPS
  .flatMap((group) => group.scopes)
  .reduce((acc, scope) => ({ ...acc, [scope.value]: scope.label }), {})
