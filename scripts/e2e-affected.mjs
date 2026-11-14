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
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

const REPO = new URL('..', import.meta.url).pathname.replace(/\/$/, '')
const E2E = join(REPO, 'e2e')
const SCEN = 'tests/scenarios'

// ════════════════════════════════════════════════════════════════════════════
// 1. spec 宇宙集（**运行时发现**）+ 策展分组（键 = 场景名）
//
// 2026-11-14 结构重构（编号从路径里去掉、目录改语义化三层）后，这里改成「路径只出现在解析处」：
//   · 宇宙集   walk e2e/tests/scenarios/**/*.spec.js —— **不再硬编码清单**。
//     为什么必须这样：旧版硬编码 47 条，磁盘上实际 54 条，08 的 5 条 + 09 的 2 条从未登记，
//     `--affected` 永远选不到它们（改了代码也不会跑）—— 清单式写法必然随目录改名一起漂。
//   · 策展分组 键从「spec 文件路径」换成**场景名**（库存台账与对账 / 平台重推与503重试 …）。
//     桶、子分类怎么抽怎么并，下面这张表一个字都不用改 —— 它只认「哪个场景会被这次改动波及」。
//   · 解析    场景名 → spec 文件：`<场景名>.spec.js` 或 `<场景名>-<用例组>.spec.js`（`-` 是边界，
//     所以「库存台账与对账」不会误吃「库存台账与对账-边界」）。
//     0 命中 → 立刻 exit 2 并点名（策展表写了个不存在的场景，等价于这条规则失效，绝不能静默）；
//     命中 >1 → 合法（一个场景可以有多条 spec，比如 08 的 5 条按内容分挂「判定链与四结局」(3) 与「六笔单走查」(2)），报告里列出来。
//   · legacy   `e2e/tests/scenarios/_legacy/*.spec.js` 是跨场景回归/历史脚本，没有对应场景目录，
//     用保留键 `@legacy/<文件名>` 引用。
// ════════════════════════════════════════════════════════════════════════════
const LEGACY_PREFIX = '@legacy/'

/** 运行时发现全部 spec（相对 e2e/ 的路径）。支持任意深度：tests/scenarios/<桶>/[<子分类>/]<场景>[-<用例组>].spec.js */
function discoverSpecs() {
  const out = []
  const stack = [SCEN]
  while (stack.length) {
    const rel = stack.pop()
    let entries
    try { entries = readdirSync(join(E2E, rel), { withFileTypes: true }) } catch { continue }
    for (const e of entries) {
      const childRel = `${rel}/${e.name}`
      if (e.isDirectory()) stack.push(childRel)
      else if (e.name.endsWith('.spec.js')) out.push(childRel)
    }
  }
  return out.sort()
}

const ALL_SPECS = discoverSpecs()
/** basename（去掉目录与 .spec.js）—— 桶搬家时它不变，所以它是策展表与权重表的稳定键 */
const baseOf = (rel) => rel.slice(rel.lastIndexOf('/') + 1).replace(/\.spec\.js$/, '')
const SPEC_BASES = ALL_SPECS.map((rel) => ({ rel, base: baseOf(rel) }))

/** 场景名 / @legacy 键 → spec 路径数组（0 命中就抛，>1 记录进 MULTI_LOG 供报告输出） */
const MULTI_LOG = []
function resolveScene(name) {
  if (name.startsWith(LEGACY_PREFIX)) {
    const rel = `${SCEN}/_legacy/${name.slice(LEGACY_PREFIX.length)}.spec.js`
    if (!ALL_SPECS.includes(rel)) {
      throw new Error(`策展表引用了不存在的 legacy spec：${name}（期望 ${rel}）`)
    }
    return [rel]
  }
  const hits = SPEC_BASES.filter((x) => x.base === name || x.base.startsWith(`${name}-`))
  if (!hits.length) {
    throw new Error(`策展表引用了不存在的场景：${name}（e2e/tests/scenarios 下没有 "${name}.spec.js" 也没有 "${name}-*.spec.js"）`)
  }
  if (hits.length > 1) MULTI_LOG.push({ scene: name, files: hits.map((h) => h.base) })
  return hits.map((h) => h.rel)
}

// 细分分组：只写「这一片代码被改，会波及哪几个场景」。**值是场景名，不是路径**
const G = {
  // ── 业务 · 买家侧 ──
  buyerAuth: ['注册与登录'],
  buyerBrowse: ['商品浏览与搜索', 'scenario1-browse', 'image-chain'].map((x) => (x.startsWith('scenario') || x === 'image-chain' ? `@legacy/${x}` : x)),
  buyerCart: ['购物车'],
  buyerCheckout: ['结算下单与支付', '@legacy/scenario2-checkout'],
  buyerPay: ['结算下单与支付', '支付并发冲突', '支付流水'],
  buyerOrders: ['我的订单与收货', '确认收货并发', '条件更新'],
  buyerReturn: ['售后退货', '@legacy/scenario4-return'],
  buyerAddress: ['收货地址'],
  // ── 业务 · 商品域 ──
  productMgmt: ['商品类型与属性', '商品分类', '商品维护', 'SKU与规格', '商品编辑并发', 'SKU编辑并发'],
  productImage: ['图片上传与预览', '@legacy/image-chain'],
  // ── 业务 · 履约域 ──
  fulfillmentAudit: ['订单审核与发货', '订单审核并发'],
  fulfillmentSplit: ['跨仓拆单', '发货单调整', '@legacy/scenario5-split', '@legacy/scenario3-fulfillment'],
  fulfillmentShip: ['订单审核与发货', '发货单与仓库回传', '订单删除守卫', '@legacy/scenario3-fulfillment'],
  fulfillmentReturn: ['退货处理', '退货审核并发', '售后台账'],
  // ── 台账与取证 ──
  ledger: ['订单状态台账', '后台操作审计', '包裹状态台账', '售后台账', 'WMS交换台账', '支付流水'],
  ledgerStock: ['库存台账与对账'],
  auditTrail: ['后台操作审计'],
  // ── 业务 · 基础配置 ──
  cfgWarehouse: ['仓库管理', '跨仓拆单'],
  cfgOrder: ['订单设置', '订单审核与发货'],
  cfgReturnReason: ['退货原因字典', '售后退货'],
  // ── 业务 · 账号与会员 ──
  adminAuth: ['管理员登录与验证码'],
  adminAccount: ['管理员账号', '后台操作审计'],
  memberMgmt: ['会员管理', '会员资料编辑并发'],
  // ── 08 订单自动审核 / 09 渠道接入 ──
  // 08 一个场景挂多条 spec：'判定链与四结局' 解析到 3 条，'六笔单走查' 解析到 2 条（见 resolveScene 的多命中日志）。
  orderAutoAudit: ['判定链与四结局', '六笔单走查'],
  channelPlatform: ['接入主链路', '平台重推与503重试'],
  // ── 其它 ──
  dashboard: ['概览页'],
  aiKey: ['令牌管理'],
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
  { re: new RegExp(`^${S}/shared/ledger/`), scenes: G.ledger, why: 'shared/ledger → 台账七类六页' },
  { re: new RegExp(`^${S}/shared/`), full: 'shared/common|config|security|web|guard|storage 改动 → 横切面，跑全量' },
  { re: /^qiqi-mall-server\/pom\.xml$/, full: 'pom.xml 改动 → 跑全量' },

  // —— 后端业务包 ——
  { re: new RegExp(`^${S}/oms/`), scenes: [...new Set([...G.fulfillmentAudit, ...G.fulfillmentSplit, ...G.fulfillmentShip, ...G.fulfillmentReturn, ...G.buyerOrders, ...G.buyerCheckout])], why: 'oms（订单/发货单/WMS）→ 履约 + 买家下单与收货 + scenario3/5' },
  { re: new RegExp(`^${S}/order/`), scenes: [...new Set([...G.buyerCheckout, ...G.buyerPay, ...G.buyerOrders, ...G.fulfillmentAudit, ...G.ledger])], why: 'order（订单实体/流水）→ 下单支付、订单列表、审核、台账' },
  { re: new RegExp(`^${S}/catalog/`), scenes: [...new Set([...G.productMgmt, ...G.productImage, ...G.buyerBrowse])], why: 'catalog（商品/SKU/分类）→ 02 商品管理 + 买家浏览 + 图片链' },
  { re: new RegExp(`^${S}/stock/`), scenes: [...new Set([...G.buyerOrders, ...G.buyerCheckout, ...G.fulfillmentSplit, ...G.fulfillmentShip, ...G.ledgerStock, ...G.cfgWarehouse])], why: 'stock（库存桶/扣减）→ 抢购、下单、拆单下发、库存台账、仓库' },
  { re: new RegExp(`^${S}/audit/`), scenes: [...new Set([...G.auditTrail, ...G.adminAccount, ...G.orderAutoAudit])], why: 'audit（判定引擎/操作审计）→ 08 自动审核两页 + 11-后台操作审计' },
  { re: new RegExp(`^${S}/channel/`), scenes: [...new Set([...G.channelPlatform])], why: 'channel（多平台接入）→ 接入主链路 + 平台重推与503重试' },
  { re: /^mock-platform\//, scenes: [...new Set([...G.channelPlatform])], why: 'mock-platform（外部平台模拟器）→ 09 渠道接入两条 spec' },
  { re: new RegExp(`^${S}/member/`), scenes: [...new Set([...G.memberMgmt, ...G.buyerAuth, ...G.buyerAddress])], why: 'member（会员/地址）→ 05 会员 + 买家注册/地址' },
  { re: new RegExp(`^${S}/portal/`), scenes: [...new Set([...G.buyerAuth, ...G.buyerBrowse, ...G.buyerCart, ...G.buyerCheckout, ...G.buyerOrders, ...G.buyerReturn, ...G.buyerAddress])], why: 'portal controller（买家接口）→ 01 买家侧全量场景' },
  { re: new RegExp(`^${S}/apikey/`), scenes: G.aiKey, why: 'apikey → 07-令牌管理' },
  { re: /^qiqi-mall-server\/src\/main\/java\/com\/qiqi\/mall\/QiQiMallApplication\.java$/, full: '启动类改动 → 跑全量' },
  // 后端测试代码不影响被测物
  { re: /^qiqi-mall-server\/src\/test\//, none: true, why: '后端单测代码改动 → 不需要 e2e' },

  // —— 前端：admin-web ——
  { re: /^admin-web\/src\/views\/product(-type)?\//, scenes: [...new Set([...G.productMgmt, ...G.productImage])], why: 'admin product 页 → 02 商品管理' },
  { re: /^admin-web\/src\/views\/category\//, scenes: G.productMgmt, why: 'admin category 页 → 02 商品管理' },
  { re: /^admin-web\/src\/views\/order\//, scenes: [...new Set([...G.fulfillmentAudit, ...G.fulfillmentSplit, ...G.fulfillmentShip])], why: 'admin order 页 → 履约 1/2/3/6/8' },
  { re: /^admin-web\/src\/views\/return-apply\//, scenes: [...new Set([...G.fulfillmentReturn])], why: 'admin return-apply 页 → 退货处理/退货审核/售后台账' },
  { re: /^admin-web\/src\/views\/return-reason\//, scenes: G.cfgReturnReason, why: 'admin return-reason 页 → 退货原因字典 + 买家退货' },
  { re: /^admin-web\/src\/views\/warehouse\//, scenes: G.cfgWarehouse, why: 'admin warehouse 页 → 仓库管理 + 跨仓拆单' },
  { re: /^admin-web\/src\/views\/order-setting\//, scenes: G.cfgOrder, why: 'admin order-setting 页 → 订单设置 + 审核发货' },
  { re: /^admin-web\/src\/views\/member\//, scenes: G.memberMgmt, why: 'admin member 页 → 05 会员' },
  { re: /^admin-web\/src\/views\/(ledger|inventory-reconcile)\//, scenes: G.ledger, why: 'admin 台账/对账页 → 台账 9~15' },
  { re: /^admin-web\/src\/views\/dashboard\//, scenes: G.dashboard, why: 'admin dashboard 页 → 06 概览' },
  { re: /^admin-web\/src\/views\/api-key\//, scenes: G.aiKey, why: 'admin api-key 页 → 07 令牌管理' },
  { re: /^admin-web\/src\/views\/login\//, scenes: [...new Set([...G.adminAuth, ...G.adminAccount])], why: 'admin login 页 → 05 登录/账号' },
  { re: /^admin-web\/src\/views\/admin\//, scenes: G.adminAccount, why: 'admin 账号管理页 → 05-2 管理员账号' },
  { re: /^admin-web\/src\//, full: 'admin-web 公共层（api/router/utils/components/layout…）→ 跑全量' },

  // —— 前端：portal-web ——
  { re: /^portal-web\/src\/views\/home\//, scenes: [...new Set([...G.buyerBrowse])], why: 'portal home 页 → 浏览/搜索 + scenario1 + 图片链' },
  { re: /^portal-web\/src\/views\/product\//, scenes: [...new Set([...G.buyerBrowse, ...G.buyerOrders, ...G.productImage])], why: 'portal product 页 → 浏览/详情 + 抢购 + 图片链' },
  { re: /^portal-web\/src\/views\/cart\//, scenes: G.buyerCart, why: 'portal cart 页 → 03 购物车' },
  { re: /^portal-web\/src\/views\/checkout\//, scenes: G.buyerCheckout, why: 'portal checkout 页 → 04 结算下单' },
  { re: /^portal-web\/src\/views\/order\//, scenes: [...new Set([...G.buyerOrders, ...G.buyerReturn, ...G.buyerPay])], why: 'portal order 页 → 我的订单/售后/支付/收货' },
  { re: /^portal-web\/src\/views\/address\//, scenes: G.buyerAddress, why: 'portal address 页 → 07 收货地址' },
  { re: /^portal-web\/src\/views\/login\//, scenes: G.buyerAuth, why: 'portal login 页 → 01-1 注册登录' },
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

// ── 规则规范化：scenes（场景名）→ specs（真实文件路径）──
// 这一步就是「路径只出现在解析处」的全部实现：策展表写场景名，运行时解析成文件。
// 任何一条引用不存在 → 直接抛错退出（等价于那条规则失效，绝不能静默放过）。
const RESOLVED = []
for (const r of RULES) {
  if (!r.scenes) continue
  const files = [...new Set(r.scenes.flatMap(resolveScene))]
  r.specs = files
  RESOLVED.push({ why: r.why, scenes: r.scenes, files })
}

// ── 兜底：规则表没覆盖到的路径一律升级为全量 ──
const FULL_THRESHOLD = Number(
  (process.argv.find((a) => a.startsWith('--full-threshold=')) || '').split('=')[1] || 0.7
)

// ════════════════════════════════════════════════════════════════════════════
// 3. 实测权重（秒）：2026-11-13 全量跑批逐条 spec 墙钟，供 --map 做 LPT 装箱
// ════════════════════════════════════════════════════════════════════════════
const MEASURED_SEC = {
  // 键 = spec 的 basename（去掉目录与 .spec.js）——桶/子分类搬家时不变，只有改名才要跟着改。
  // 2026-11-13 全量跑批逐条墙钟；新加/改名的 spec 没在这里就退化用「用例数 × 经验系数」。
  '注册与登录': 28, '商品浏览与搜索': 36, '购物车': 61, '结算下单与支付': 68,
  '我的订单与收货': 90, '售后退货': 153, '收货地址': 53,
  '支付并发冲突': 46, '确认收货并发': 39, '条件更新': 46,
  '商品类型与属性': 42, '商品分类': 40, '商品维护': 55, 'SKU与规格': 79,
  '图片上传与预览': 59, '商品编辑并发': 23, 'SKU编辑并发': 33,
  '订单审核与发货': 64, '跨仓拆单': 50, '发货单调整': 136, '退货处理': 180,
  '订单审核并发': 34, '发货单与仓库回传': 60, '退货审核并发': 46, '订单删除守卫': 86,
  '库存台账与对账': 60, '订单状态台账': 40, '后台操作审计': 40,
  '包裹状态台账': 40, '售后台账': 40, 'WMS交换台账': 40, '支付流水': 30,
  '仓库管理': 34, '订单设置': 18, '退货原因字典': 74,
  '管理员登录与验证码': 92, '管理员账号': 41, '会员管理': 88, '会员资料编辑并发': 22,
  '概览页': 11, '令牌管理': 13,
  '判定链与四结局-自动过审与留人工': 40, '判定链与四结局-阻止与冻结': 40,
  '判定链与四结局-不命中与差在哪': 40, '六笔单走查-审核轨迹与对齐': 40,
  '六笔单走查-防回归': 70,
  '接入主链路': 60, '平台重推与503重试': 90,
  'image-chain': 28, 'scenario1-browse': 18, 'scenario2-checkout': 18,
  'scenario3-fulfillment': 59, 'scenario4-return': 49, 'scenario5-split': 52
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
  // ⚠ noneOnly 只在「真的挑出了 spec」时才置 false：改名/删除留下的旧 spec 路径不选任何东西，
  //   一次纯改名的 diff 不该因此升级成「全量」（那不是保守，那是白跑 20 分钟）。
  let added = 0
  if (rule.ownSpec) {
    const m = f.match(rule.re)
    const rel = `${SCEN}/${m[1]}`
    // 删除/改名留下的旧路径：磁盘上已经没有这个 spec，加进选中集合只会让 playwright 报
    // 「no tests found」。改名时新路径是未跟踪文件，会被同一条规则单独命中 —— 所以这里直接跳过。
    if (ALL_SPECS.includes(rel)) { specs.add(rel); added++; noneOnly = false } else {
      hits.push({ file: f, action: 'skip', why: 'spec 已不在磁盘上（删除/改名）→ 无需跑' })
      continue
    }
  } else {
    noneOnly = false
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
  const allSkip = hits.length > 0 && hits.every((h) => h.action === 'skip')
  reason = mode === 'none'
    ? (allSkip
      ? `改动只涉及已删除/改名的 spec（${files.length} 个文件）→ 无需跑 e2e`
      : `改动只涉及文档/物料（${files.length} 个文件）→ 无需跑 e2e`)
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
    const short = baseOf(rel)
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
