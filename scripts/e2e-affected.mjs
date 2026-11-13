#!/usr/bin/env node
/**
 * `--affected` 的映射器：按 git 改动文件 → 只挑受影响的 e2e spec。
 *
 *   node scripts/e2e-affected.mjs                       # 默认：origin/main...HEAD + 工作区改动
 *   node scripts/e2e-affected.mjs --base=HEAD~1         # 换基准
 *   node scripts/e2e-affected.mjs --shell               # 给 bash eval 用（MODE/SPECS/REASON）
 *   node scripts/e2e-affected.mjs --json                # 机器可读
 *   node scripts/e2e-affected.mjs --map --shards=2      # 只输出受影响 spec 的 LPT 分片图（TSV）
 *   node scripts/e2e-affected.mjs --paths=a/b.java,c/d.vue   # 直接给改动清单（自测/演练用，不读 git）
 *   node scripts/e2e-affected.mjs --dry                 # 只解释命中规则，不输出 spec
 *
 * ── 为什么要有它 ───────────────────────────────────────────────────────────
 * 全量 122 条要 20 分钟（4 核 + 2 分片，受 qiqi-e2e.slice 内存上限约束）。日常改一个后端包，
 * 真正受影响的往往只有几条 spec；把全量留给发布前，日常只跑被改到的场景。
 *
 * ── 三条「宁可多跑」的安全阀 ───────────────────────────────────────────────
 *  1) 改动命中 `shared/**`（除 `shared/ledger/**`）、`sql/**`、任何 `*.sql`、`application*.yml`、
 *     前端公共层（api/router/utils/components/layout/store…）→ **升级为全量**。
 *     这些是横切面：一个字段校验改了，谁都可能挂，用映射表猜「谁不受影响」是不负责任的。
 *  2) 改动只落在 `docs/**`、`*.md`、`scripts/build-material.mjs` 这类纯文档/物料 → **不跑 e2e**（MODE=none）。
 *  3) 命中规则表以外的未知路径（新增目录、构建脚本、pom.xml…）→ **升级为全量**。
 *
 * ── 另一条兜底：受影响面太大就退回全量 ─────────────────────────────────────
 * 受影响 spec 占全量 ≥ FULL_THRESHOLD（默认 70%）时，直接跑全量：省下的时间不值得多引入
 * 一套「半个全量」的分片/重置逻辑，而且全量的结论更硬。
 *
 * ── 权重（--map）──────────────────────────────────────────────────────────
 * LPT 装箱（和 scripts/e2e-shard.mjs 同一套算法），重量取下面的 MEASURED_SEC 实测表
 * （2026-11-13 全量跑批的每条 spec 墙钟秒数），表里没有的文件退化用「用例数 × 经验系数」。
 * 为什么不复用 e2e-shard.mjs：它只认 `e2e/report/runs.jsonl` 的全量历史，且不接受子集过滤；
 * 受影响集合通常只有 2~10 个文件，直接在这里装箱更准，也不用去改那个脚本。
 */
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const REPO = new URL('..', import.meta.url).pathname.replace(/\/$/, '')
const E2E = join(REPO, 'e2e')
const SCEN = 'tests/scenarios'

// ════════════════════════════════════════════════════════════════════════════
// 1. spec 分组（相对 e2e/ 的路径）
// ════════════════════════════════════════════════════════════════════════════
const p = (...names) => names.map((n) => `${SCEN}/${n}`)

const ALL_SPECS = [
  ...p('01-买家侧/1-注册与登录.spec.js', '01-买家侧/2-商品浏览与搜索.spec.js', '01-买家侧/3-购物车.spec.js',
    '01-买家侧/4-结算下单与支付.spec.js', '01-买家侧/5-我的订单与收货.spec.js', '01-买家侧/6-售后退货.spec.js',
    '01-买家侧/7-收货地址.spec.js', '01-买家侧/8-支付并发冲突.spec.js', '01-买家侧/9-确认收货并发.spec.js',
    '01-买家侧/10-同商品多人抢购库存.spec.js'),
  ...p('02-商家-商品管理/1-商品类型与属性.spec.js', '02-商家-商品管理/2-商品分类.spec.js',
    '02-商家-商品管理/3-商品维护.spec.js', '02-商家-商品管理/4-SKU与规格.spec.js',
    '02-商家-商品管理/5-图片上传与预览.spec.js', '02-商家-商品管理/6-商品编辑并发冲突.spec.js',
    '02-商家-商品管理/7-SKU编辑并发冲突.spec.js'),
  ...p('03-商家-履约/1-订单审核与发货.spec.js', '03-商家-履约/2-跨仓拆单.spec.js',
    '03-商家-履约/3-发货单调整.spec.js', '03-商家-履约/4-退货处理.spec.js',
    '03-商家-履约/5-订单审核并发.spec.js', '03-商家-履约/6-发货单与仓库回传并发.spec.js',
    '03-商家-履约/7-退货审核并发.spec.js', '03-商家-履约/8-订单删除守卫.spec.js',
    '03-商家-履约/9-库存台账与对账.spec.js', '03-商家-履约/10-订单状态台账.spec.js',
    '03-商家-履约/11-后台操作审计.spec.js', '03-商家-履约/12-包裹状态台账.spec.js',
    '03-商家-履约/13-售后台账.spec.js', '03-商家-履约/14-WMS交换台账.spec.js',
    '03-商家-履约/15-支付流水加固.spec.js'),
  ...p('04-商家-基础配置/1-仓库管理.spec.js', '04-商家-基础配置/2-订单设置.spec.js',
    '04-商家-基础配置/3-退货原因字典.spec.js'),
  ...p('05-账号与会员/1-管理员登录与验证码.spec.js', '05-账号与会员/2-管理员账号.spec.js',
    '05-账号与会员/3-会员管理.spec.js', '05-账号与会员/4-会员资料编辑并发.spec.js'),
  ...p('06-数据概览/1-概览页.spec.js'),
  ...p('07-AI集成/4-令牌管理.spec.js'),
  ...p('image-chain.spec.js', 'scenario1-browse.spec.js', 'scenario2-checkout.spec.js',
    'scenario3-fulfillment.spec.js', 'scenario4-return.spec.js', 'scenario5-split.spec.js')
]

// 细分分组：映射表只写「这一片代码被改，会波及哪几条 spec」
const g = (...paths) => paths.map((x) => `${SCEN}/${x}`)
const G = {
  // ── 买家侧 ──
  buyerAuth: g('01-买家侧/1-注册与登录.spec.js'),
  buyerBrowse: g('01-买家侧/2-商品浏览与搜索.spec.js', 'scenario1-browse.spec.js', 'image-chain.spec.js'),
  buyerCart: g('01-买家侧/3-购物车.spec.js'),
  buyerCheckout: g('01-买家侧/4-结算下单与支付.spec.js', 'scenario2-checkout.spec.js'),
  buyerPay: g('01-买家侧/4-结算下单与支付.spec.js', '01-买家侧/8-支付并发冲突.spec.js', '03-商家-履约/15-支付流水加固.spec.js'),
  buyerOrders: g('01-买家侧/5-我的订单与收货.spec.js', '01-买家侧/9-确认收货并发.spec.js', '01-买家侧/10-同商品多人抢购库存.spec.js'),
  buyerReturn: g('01-买家侧/6-售后退货.spec.js', 'scenario4-return.spec.js'),
  buyerAddress: g('01-买家侧/7-收货地址.spec.js'),
  // ── 商家：商品域 ──
  productMgmt: g('02-商家-商品管理/1-商品类型与属性.spec.js', '02-商家-商品管理/2-商品分类.spec.js',
    '02-商家-商品管理/3-商品维护.spec.js', '02-商家-商品管理/4-SKU与规格.spec.js',
    '02-商家-商品管理/6-商品编辑并发冲突.spec.js', '02-商家-商品管理/7-SKU编辑并发冲突.spec.js'),
  productImage: g('02-商家-商品管理/5-图片上传与预览.spec.js', 'image-chain.spec.js'),
  // ── 商家：履约域 ──
  fulfillmentAudit: g('03-商家-履约/1-订单审核与发货.spec.js', '03-商家-履约/5-订单审核并发.spec.js'),
  fulfillmentSplit: g('03-商家-履约/2-跨仓拆单.spec.js', '03-商家-履约/3-发货单调整.spec.js',
    'scenario5-split.spec.js', 'scenario3-fulfillment.spec.js'),
  fulfillmentShip: g('03-商家-履约/1-订单审核与发货.spec.js', '03-商家-履约/6-发货单与仓库回传并发.spec.js',
    '03-商家-履约/8-订单删除守卫.spec.js', 'scenario3-fulfillment.spec.js'),
  fulfillmentReturn: g('03-商家-履约/4-退货处理.spec.js', '03-商家-履约/7-退货审核并发.spec.js',
    '03-商家-履约/13-售后台账.spec.js'),
  // ── 商家：台账域（9~15）──
  ledger: g('03-商家-履约/9-库存台账与对账.spec.js', '03-商家-履约/10-订单状态台账.spec.js',
    '03-商家-履约/11-后台操作审计.spec.js', '03-商家-履约/12-包裹状态台账.spec.js',
    '03-商家-履约/13-售后台账.spec.js', '03-商家-履约/14-WMS交换台账.spec.js',
    '03-商家-履约/15-支付流水加固.spec.js'),
  ledgerStock: g('03-商家-履约/9-库存台账与对账.spec.js'),
  auditTrail: g('03-商家-履约/11-后台操作审计.spec.js'),
  // ── 商家：基础配置 ──
  cfgWarehouse: g('04-商家-基础配置/1-仓库管理.spec.js', '03-商家-履约/2-跨仓拆单.spec.js'),
  cfgOrder: g('04-商家-基础配置/2-订单设置.spec.js', '03-商家-履约/1-订单审核与发货.spec.js'),
  cfgReturnReason: g('04-商家-基础配置/3-退货原因字典.spec.js', '01-买家侧/6-售后退货.spec.js'),
  // ── 账号与会员 ──
  adminAuth: g('05-账号与会员/1-管理员登录与验证码.spec.js'),
  adminAccount: g('05-账号与会员/2-管理员账号.spec.js', '03-商家-履约/11-后台操作审计.spec.js'),
  memberMgmt: g('05-账号与会员/3-会员管理.spec.js', '05-账号与会员/4-会员资料编辑并发.spec.js'),
  // ── 其它 ──
  dashboard: g('06-数据概览/1-概览页.spec.js'),
  aiKey: g('07-AI集成/4-令牌管理.spec.js')
}

// ════════════════════════════════════════════════════════════════════════════
// 2. 改动 → 分组 的映射表（顺序敏感：先匹配先赢）
// ════════════════════════════════════════════════════════════════════════════
const S = 'qiqi-mall-server/src/main/java/com/qiqi/mall'
const RULES = [
  // —— 安全阀：横切面 → 全量 ——
  { re: /\.sql$/i, full: '碰到 SQL/migration（含 sql/**、*/migration/**）→ 数据形状可能变了，跑全量' },
  { re: /^sql\//, full: 'sql/** 改动 → 跑全量' },
  { re: /^qiqi-mall-server\/src\/main\/resources\//, full: 'application*.yml / 资源文件改动 → 跑全量' },
  // shared/ledger 是台账专用、有明确的一对一场景，先于 shared/** 的总闸匹配
  { re: new RegExp(`^${S}/shared/ledger/`), specs: G.ledger, why: 'shared/ledger → 台账 9~15' },
  { re: new RegExp(`^${S}/shared/`), full: 'shared/common|config|security|web|guard|storage 改动 → 横切面，跑全量' },
  { re: /^qiqi-mall-server\/pom\.xml$/, full: 'pom.xml 改动 → 跑全量' },

  // —— 后端业务包 ——
  { re: new RegExp(`^${S}/oms/`), specs: [...new Set([...G.fulfillmentAudit, ...G.fulfillmentSplit, ...G.fulfillmentShip, ...G.fulfillmentReturn, ...G.buyerOrders, ...G.buyerCheckout])], why: 'oms（订单/发货单/WMS）→ 履约 + 买家下单与收货 + scenario3/5' },
  { re: new RegExp(`^${S}/order/`), specs: [...new Set([...G.buyerCheckout, ...G.buyerPay, ...G.buyerOrders, ...G.fulfillmentAudit, ...G.ledger])], why: 'order（订单实体/流水）→ 下单支付、订单列表、审核、台账' },
  { re: new RegExp(`^${S}/catalog/`), specs: [...new Set([...G.productMgmt, ...G.productImage, ...G.buyerBrowse])], why: 'catalog（商品/SKU/分类）→ 02 商品管理 + 买家浏览 + 图片链' },
  { re: new RegExp(`^${S}/stock/`), specs: [...new Set([...G.buyerOrders, ...G.buyerCheckout, ...G.fulfillmentSplit, ...G.fulfillmentShip, ...G.ledgerStock, ...G.cfgWarehouse])], why: 'stock（库存桶/扣减）→ 抢购、下单、拆单下发、库存台账、仓库' },
  { re: new RegExp(`^${S}/audit/`), specs: [...new Set([...G.auditTrail, ...G.adminAccount])], why: 'audit（操作审计）→ 11-后台操作审计' },
  { re: new RegExp(`^${S}/member/`), specs: [...new Set([...G.memberMgmt, ...G.buyerAuth, ...G.buyerAddress])], why: 'member（会员/地址）→ 05 会员 + 买家注册/地址' },
  { re: new RegExp(`^${S}/portal/`), specs: [...new Set([...G.buyerAuth, ...G.buyerBrowse, ...G.buyerCart, ...G.buyerCheckout, ...G.buyerOrders, ...G.buyerReturn, ...G.buyerAddress])], why: 'portal controller（买家接口）→ 01 买家侧全量场景' },
  { re: new RegExp(`^${S}/apikey/`), specs: G.aiKey, why: 'apikey → 07-令牌管理' },
  { re: /^qiqi-mall-server\/src\/main\/java\/com\/qiqi\/mall\/QiQiMallApplication\.java$/, full: '启动类改动 → 跑全量' },
  // 后端测试代码不影响被测物
  { re: /^qiqi-mall-server\/src\/test\//, none: true, why: '后端单测代码改动 → 不需要 e2e' },

  // —— 前端：admin-web ——
  { re: /^admin-web\/src\/views\/product(-type)?\//, specs: [...new Set([...G.productMgmt, ...G.productImage])], why: 'admin product 页 → 02 商品管理' },
  { re: /^admin-web\/src\/views\/category\//, specs: G.productMgmt, why: 'admin category 页 → 02 商品管理' },
  { re: /^admin-web\/src\/views\/order\//, specs: [...new Set([...G.fulfillmentAudit, ...G.fulfillmentSplit, ...G.fulfillmentShip])], why: 'admin order 页 → 履约 1/2/3/6/8' },
  { re: /^admin-web\/src\/views\/return-apply\//, specs: [...new Set([...G.fulfillmentReturn])], why: 'admin return-apply 页 → 退货处理/退货审核/售后台账' },
  { re: /^admin-web\/src\/views\/return-reason\//, specs: G.cfgReturnReason, why: 'admin return-reason 页 → 退货原因字典 + 买家退货' },
  { re: /^admin-web\/src\/views\/warehouse\//, specs: G.cfgWarehouse, why: 'admin warehouse 页 → 仓库管理 + 跨仓拆单' },
  { re: /^admin-web\/src\/views\/order-setting\//, specs: G.cfgOrder, why: 'admin order-setting 页 → 订单设置 + 审核发货' },
  { re: /^admin-web\/src\/views\/member\//, specs: G.memberMgmt, why: 'admin member 页 → 05 会员' },
  { re: /^admin-web\/src\/views\/(ledger|inventory-reconcile)\//, specs: G.ledger, why: 'admin 台账/对账页 → 台账 9~15' },
  { re: /^admin-web\/src\/views\/dashboard\//, specs: G.dashboard, why: 'admin dashboard 页 → 06 概览' },
  { re: /^admin-web\/src\/views\/api-key\//, specs: G.aiKey, why: 'admin api-key 页 → 07 令牌管理' },
  { re: /^admin-web\/src\/views\/login\//, specs: [...new Set([...G.adminAuth, ...G.adminAccount])], why: 'admin login 页 → 05 登录/账号' },
  { re: /^admin-web\/src\/views\/admin\//, specs: G.adminAccount, why: 'admin 账号管理页 → 05-2 管理员账号' },
  { re: /^admin-web\/src\//, full: 'admin-web 公共层（api/router/utils/components/layout…）→ 跑全量' },

  // —— 前端：portal-web ——
  { re: /^portal-web\/src\/views\/home\//, specs: [...new Set([...G.buyerBrowse])], why: 'portal home 页 → 浏览/搜索 + scenario1 + 图片链' },
  { re: /^portal-web\/src\/views\/product\//, specs: [...new Set([...G.buyerBrowse, ...G.buyerOrders, ...G.productImage])], why: 'portal product 页 → 浏览/详情 + 抢购 + 图片链' },
  { re: /^portal-web\/src\/views\/cart\//, specs: G.buyerCart, why: 'portal cart 页 → 03 购物车' },
  { re: /^portal-web\/src\/views\/checkout\//, specs: G.buyerCheckout, why: 'portal checkout 页 → 04 结算下单' },
  { re: /^portal-web\/src\/views\/order\//, specs: [...new Set([...G.buyerOrders, ...G.buyerReturn, ...G.buyerPay])], why: 'portal order 页 → 我的订单/售后/支付/收货' },
  { re: /^portal-web\/src\/views\/address\//, specs: G.buyerAddress, why: 'portal address 页 → 07 收货地址' },
  { re: /^portal-web\/src\/views\/login\//, specs: G.buyerAuth, why: 'portal login 页 → 01-1 注册登录' },
  { re: /^portal-web\/src\//, full: 'portal-web 公共层（api/router/utils/components/layout…）→ 跑全量' },

  // —— 测试自身：改了哪条就只跑哪条（support 是公共层 → 全量）——
  { re: /^e2e\/tests\/support\//, full: 'e2e/tests/support 公共 helper 改动 → 影响所有 spec，跑全量' },
  { re: /^e2e\/playwright\.config\.js$/, full: 'playwright.config.js 改动 → 跑全量' },
  { re: /^e2e\/tests\/scenarios\/(.+\.spec\.js)$/, ownSpec: true, why: 'spec 自身改动 → 只跑它自己' },

  // —— 纯文档 / 物料 / 跑批脚手架：不需要 e2e ——
  { re: /^docs\//, none: true, why: 'docs/** 改动 → 不需要 e2e' },
  { re: /\.md$/i, none: true, why: 'Markdown 改动 → 不需要 e2e' },
  { re: /(^|\/)build-material\.mjs$/, none: true, why: 'build-material.mjs 改动 → 不需要 e2e' },
  { re: /^scripts\/(check-docs-site|render-diagram|render-svg|mermaid-svg|fetch-seed-data|publish)\.(mjs|sh|json)$/, none: true, why: '文档/物料脚本改动 → 不需要 e2e' },
  // 跑批脚手架自身（本文件、e2e-run.sh 等）改了要自证不坏 → 跑全量
  { re: /^scripts\/e2e-/, full: 'e2e 跑批脚手架改动 → 自证一遍全量' }
]

// ── 兜底：规则表没覆盖到的路径一律升级为全量 ──
const FULL_THRESHOLD = Number(
  (process.argv.find((a) => a.startsWith('--full-threshold=')) || '').split('=')[1] || 0.7
)

// ════════════════════════════════════════════════════════════════════════════
// 3. 实测权重（秒）：2026-11-13 全量跑批逐条 spec 墙钟，供 --map 做 LPT 装箱
// ════════════════════════════════════════════════════════════════════════════
const MEASURED_SEC = {
  '01-买家侧/1-注册与登录.spec.js': 28, '01-买家侧/2-商品浏览与搜索.spec.js': 36, '01-买家侧/3-购物车.spec.js': 61,
  '01-买家侧/4-结算下单与支付.spec.js': 68, '01-买家侧/5-我的订单与收货.spec.js': 90, '01-买家侧/6-售后退货.spec.js': 153,
  '01-买家侧/7-收货地址.spec.js': 53, '01-买家侧/8-支付并发冲突.spec.js': 46, '01-买家侧/9-确认收货并发.spec.js': 39,
  '01-买家侧/10-同商品多人抢购库存.spec.js': 46,
  '02-商家-商品管理/1-商品类型与属性.spec.js': 42, '02-商家-商品管理/2-商品分类.spec.js': 40,
  '02-商家-商品管理/3-商品维护.spec.js': 55, '02-商家-商品管理/4-SKU与规格.spec.js': 79,
  '02-商家-商品管理/5-图片上传与预览.spec.js': 59, '02-商家-商品管理/6-商品编辑并发冲突.spec.js': 23,
  '02-商家-商品管理/7-SKU编辑并发冲突.spec.js': 33,
  '03-商家-履约/1-订单审核与发货.spec.js': 64, '03-商家-履约/2-跨仓拆单.spec.js': 50,
  '03-商家-履约/3-发货单调整.spec.js': 136, '03-商家-履约/4-退货处理.spec.js': 180,
  '03-商家-履约/5-订单审核并发.spec.js': 34, '03-商家-履约/6-发货单与仓库回传并发.spec.js': 60,
  '03-商家-履约/7-退货审核并发.spec.js': 46, '03-商家-履约/8-订单删除守卫.spec.js': 86,
  '03-商家-履约/9-库存台账与对账.spec.js': 60, '03-商家-履约/10-订单状态台账.spec.js': 40,
  '03-商家-履约/11-后台操作审计.spec.js': 40, '03-商家-履约/12-包裹状态台账.spec.js': 40,
  '03-商家-履约/13-售后台账.spec.js': 40, '03-商家-履约/14-WMS交换台账.spec.js': 40,
  '03-商家-履约/15-支付流水加固.spec.js': 30,
  '04-商家-基础配置/1-仓库管理.spec.js': 34, '04-商家-基础配置/2-订单设置.spec.js': 18,
  '04-商家-基础配置/3-退货原因字典.spec.js': 74,
  '05-账号与会员/1-管理员登录与验证码.spec.js': 92, '05-账号与会员/2-管理员账号.spec.js': 41,
  '05-账号与会员/3-会员管理.spec.js': 88, '05-账号与会员/4-会员资料编辑并发.spec.js': 22,
  '06-数据概览/1-概览页.spec.js': 11, '07-AI集成/4-令牌管理.spec.js': 13,
  'image-chain.spec.js': 28, 'scenario1-browse.spec.js': 18, 'scenario2-checkout.spec.js': 18,
  'scenario3-fulfillment.spec.js': 59, 'scenario4-return.spec.js': 49, 'scenario5-split.spec.js': 52
}

// ════════════════════════════════════════════════════════════════════════════
// 4. 取改动清单
// ════════════════════════════════════════════════════════════════════════════
const argOf = (name, dflt = '') => {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`))
  return hit ? hit.split('=').slice(1).join('=') : dflt
}
const BASE = argOf('base')

const git = (args) => {
  try {
    return execFileSync('git', args, { cwd: REPO, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
  } catch {
    return ''
  }
}

/** 默认基准：origin/main 存在就用它，否则 HEAD~1（新仓库只有一个 commit 时退回空树） */
function defaultBase() {
  if (BASE) return BASE
  if (git(['rev-parse', '--verify', '--quiet', 'origin/main']).trim()) return 'origin/main'
  if (git(['rev-parse', '--verify', '--quiet', 'HEAD~1']).trim()) return 'HEAD~1'
  return ''
}

/** 改动清单 = 与基准的分叉点→HEAD 的提交 + 工作区（含未跟踪文件） */
function changedFiles() {
  const direct = argOf('paths')
  if (direct) return direct.split(',').map((s) => s.trim()).filter(Boolean)

  const base = defaultBase()
  const out = new Set()
  if (base) {
    const mergeBase = git(['merge-base', base, 'HEAD']).trim() || base
    for (const l of git(['diff', '--name-only', mergeBase, 'HEAD']).split('\n')) if (l.trim()) out.add(l.trim())
  }
  for (const l of git(['diff', '--name-only', 'HEAD']).split('\n')) if (l.trim()) out.add(l.trim())
  for (const l of git(['ls-files', '--others', '--exclude-standard']).split('\n')) if (l.trim()) out.add(l.trim())
  return [...out]
}

// ════════════════════════════════════════════════════════════════════════════
// 5. 映射
// ════════════════════════════════════════════════════════════════════════════
const files = changedFiles().map((f) => f.replace(/^\.\//, ''))

let full = null // 一旦命中「安全阀」就记下原因
let noneOnly = files.length > 0
const hits = []
const specs = new Set()

for (const f of files) {
  const rule = RULES.find((r) => r.re.test(f))
  if (!rule) {
    full = full || `未收录的路径 ${f} → 保守起见跑全量`
    hits.push({ file: f, action: 'full', why: '未收录路径（兜底）' })
    continue
  }
  if (rule.full) {
    full = full || `${f}：${rule.full}`
    hits.push({ file: f, action: 'full', why: rule.full })
    continue
  }
  if (rule.none) {
    hits.push({ file: f, action: 'none', why: rule.why })
    continue
  }
  noneOnly = false
  let added = 0
  if (rule.ownSpec) {
    const m = f.match(rule.re)
    specs.add(`${SCEN}/${m[1]}`); added++
  } else {
    for (const s of rule.specs) { specs.add(s); added++ }
  }
  hits.push({ file: f, action: 'spec', why: rule.why || '', specs: added })
}

// ── 定档 ──
let mode = 'subset'
let reason = ''
if (!files.length) {
  mode = 'none'; reason = '没有检测到改动（对比基准为空）→ 无需跑 e2e'
} else if (full) {
  mode = 'full'; reason = full
} else if (!specs.size) {
  mode = noneOnly ? 'none' : 'full'
  reason = mode === 'none'
    ? `改动只涉及文档/物料（${files.length} 个文件）→ 无需跑 e2e`
    : '改动没有命中任何 spec 映射 → 保守起见跑全量'
} else if (specs.size >= ALL_SPECS.length * FULL_THRESHOLD) {
  mode = 'full'
  reason = `受影响 spec ${specs.size}/${ALL_SPECS.length} ≥ ${Math.round(FULL_THRESHOLD * 100)}% → 直接跑全量更省事也更硬`
} else {
  reason = `命中 ${specs.size} 个 spec（全量 ${ALL_SPECS.length} 个）`
}

const picked = [...specs].sort()

// ════════════════════════════════════════════════════════════════════════════
// 6. 输出
// ════════════════════════════════════════════════════════════════════════════
const wantMap = process.argv.includes('--map')
const SHARDS = Math.max(1, Number(argOf('shards', '1')))

/** LPT 装箱：和 e2e-shard.mjs 同一个算法，重量取实测秒数（缺表则 用例数×经验系数） */
function lpt(specPaths, shards) {
  const items = specPaths.map((rel) => {
    const short = rel.replace(`${SCEN}/`, '')
    const abs = join(E2E, rel)
    let sec = MEASURED_SEC[short]
    if (!sec) {
      const src = existsSync(abs) ? readFileSync(abs, 'utf8') : ''
      const tests = (src.match(/^\s*test\s*\(/gm) || []).length || 1
      sec = tests * 30
    }
    return { rel, sec }
  })
  const n = Math.max(1, Math.min(shards, items.length))
  const buckets = Array.from({ length: n }, () => ({ load: 0, files: [] }))
  for (const it of [...items].sort((a, b) => b.sec - a.sec)) {
    const target = buckets.reduce((m, b) => (b.load < m.load ? b : m), buckets[0])
    target.load += it.sec
    target.files.push(it.rel)
  }
  return buckets
}

const NAMES = 'abcd'.split('')
if (wantMap) {
  // 只输出「受影响集合」的分片图；MODE=full 时由调用方自己走全量分片
  if (mode !== 'subset') {
    process.stderr.write(`mode=${mode}，不出分片图（${reason}）\n`)
    process.exit(0)
  }
  for (const [i, b] of lpt(picked, SHARDS).entries()) {
    if (!b.files.length) continue
    process.stdout.write([NAMES[i], ...b.files.sort()].join('\t') + '\n')
  }
  process.stderr.write(`受影响 ${picked.length} 个 spec 装进 ${Math.min(SHARDS, picked.length)} 个 shard（LPT/实测秒）\n`)
  for (const [i, b] of lpt(picked, SHARDS).entries()) {
    if (!b.files.length) continue
    process.stderr.write(`  shard ${NAMES[i]}: ${b.files.length} 文件 / ~${Math.round(b.load)}s\n`)
  }
} else if (process.argv.includes('--json')) {
  process.stdout.write(JSON.stringify({ mode, reason, base: defaultBase(), changed: files, specs: picked, hits }, null, 2) + '\n')
} else if (process.argv.includes('--shell')) {
  // 给 bash eval 用；spec 路径里没有空格，但仍用单引号包一层
  const q = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`
  process.stdout.write(`MODE=${q(mode)}\n`)
  process.stdout.write(`REASON=${q(reason)}\n`)
  process.stdout.write(`SPECS=(${picked.map(q).join(' ')})\n`)
} else {
  process.stderr.write(`▶ --affected：基准 ${defaultBase() || '(空)'}，改动 ${files.length} 个文件 → ${mode}\n`)
  process.stderr.write(`  理由：${reason}\n`)
  for (const h of hits.slice(0, 40)) {
    process.stderr.write(`  · [${h.action}] ${h.file}${h.why ? ` —— ${h.why}` : ''}\n`)
  }
  if (!process.argv.includes('--dry') && mode === 'subset') {
    for (const s of picked) process.stdout.write(s + '\n')
  }
}
