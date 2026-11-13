#!/usr/bin/env node
/**
 * 生成测试库（qiqi_mall_test）种子数据：sql/testdb/02-seed.sql
 *
 * 商品来自 DummyJSON 公开数据（https://dummyjson.com），图片走**后端真实上传链路**
 * （POST /admin/upload/image → MinIO PutObject），所以种子里的 pic / 相册都是本机可达的
 * 真实 URL，不依赖外网、也不再出现 minio.local 这种假域名。
 *
 * 分类 / 类型 / 属性 / SKU 是本项目自己的结构，不吃外部数据的形状：
 *   - 分类：两层树，4 个根（手机数码 / 服装配饰 / 美妆个护 / 食品家居）
 *   - 类型：手机、服装、美妆、食品家居，每个类型 2 个属性（is_filter=1）
 *   - SKU：按类型的 2 个属性做 2×2 笛卡尔积 → 每个商品 4 个 SKU，规格值写进 sku_spec
 *   - SKU 图：每个 SKU 用**不同的图**（SKU1=商品主图，SKU2..4=相册里的不同角度图），
 *     真实电商不同颜色/规格本来就配不同图，也让「详情页切规格换主图」在演示里看得出来
 *   - 分仓：SKU1→深圳仓、SKU2→杭州仓、SKU3→北京仓、SKU4→深圳仓，
 *     保证"同一个商品的两个 SKU 落在不同仓"（场景5 拆单依赖），每个 SKU 库存 1000
 *
 * 文案中文化：优先读 sql/testdb/seed-i18n.json（商品标题/描述、分类、品牌）；
 * 缺翻译就回退英文原值，并在结束时的报告里列出「未翻译清单」。
 *
 * 用法：
 *   node scripts/fetch-seed-data.mjs            # 拉数据 + 传图 + 生成 02-seed.sql
 *   node scripts/fetch-seed-data.mjs --no-upload  # 只生成 SQL，图片用 cdn 原链（离线调试用）
 *
 * 需要：后端可达（默认容器栈 http://127.0.0.1:18080，可用 UPLOAD_API_BASE 覆盖）、MinIO 可用。
 * 幂等：生成的 SQL 内部先 SET FOREIGN_KEY_CHECKS=0 + TRUNCATE 再按固定 id 插入，可反复重跑。
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SCRIPTS_DIR = dirname(fileURLToPath(import.meta.url))
const REPO_DIR = resolve(SCRIPTS_DIR, '..')
const OUT_FILE = join(REPO_DIR, 'sql/testdb/02-seed.sql')
const I18N_FILE = join(REPO_DIR, 'sql/testdb/seed-i18n.json')
const API_BASE = process.env.UPLOAD_API_BASE || 'http://127.0.0.1:18080'
const NO_UPLOAD = process.argv.includes('--no-upload')

/** 精选的商品（跨 4 个根分类，尽量都有 brand / 多图） */
const PRODUCT_IDS = [
  78, 79, 80, 123, 125, 159, 160, 100, 101, // 手机数码
  83, 88, 90, 93, 98, 174, 186, 154,        // 服装配饰
  1, 2, 4, 6, 8, 119,                       // 美妆个护
  11, 14, 51, 52, 27                        // 食品家居
]

/** DummyJSON 分类 → 商品类型 id（类型是项目自己的结构） */
const CATEGORY_TYPE = {
  laptops: 1, smartphones: 1, tablets: 1, 'mobile-accessories': 1,
  'mens-shirts': 2, 'mens-shoes': 2, 'womens-shoes': 2, 'mens-watches': 2,
  'womens-bags': 2, sunglasses: 2, tops: 2, 'womens-dresses': 2,
  'womens-jewellery': 2, 'womens-watches': 2,
  beauty: 3, fragrances: 3, 'skin-care': 3,
  'kitchen-accessories': 4, furniture: 4, groceries: 4, 'home-decoration': 4,
  'sports-accessories': 4, motorcycle: 1, vehicle: 1
}

/** 4 个类型 + 每个类型 2 个属性（值用于 SKU 笛卡尔积） */
const TYPES = [
  { id: 1, name: '手机', attrs: [{ id: 1, name: '颜色', values: ['深空灰', '星光银'] }, { id: 2, name: '内存', values: ['256G', '512G'] }] },
  { id: 2, name: '服装', attrs: [{ id: 3, name: '颜色', values: ['黑色', '米白'] }, { id: 4, name: '尺码', values: ['M', 'L'] }] },
  { id: 3, name: '美妆', attrs: [{ id: 5, name: '色号', values: ['自然色', '蜜桃色'] }, { id: 6, name: '容量', values: ['30ml', '50ml'] }] },
  { id: 4, name: '食品家居', attrs: [{ id: 7, name: '口味', values: ['原味', '香辣'] }, { id: 8, name: '规格', values: ['单件装', '家庭装'] }] }
]

/** 分仓：第 i 个 SKU 放哪个仓（保证同一商品前两个 SKU 跨仓） */
const WAREHOUSES = [
  { id: 1, name: '深圳仓' },
  { id: 2, name: '杭州仓' },
  { id: 3, name: '成都仓' }
]
const SKU_WAREHOUSE_CYCLE = [1, 2, 3, 1]
const SKU_STOCK = 1000
/**
 * 库存分桶数：必须与后端配置 qiqi.stock.bucket-count（application-dev/test.yml）一致，
 * 也必须与 `sql/_rollback/migrate-sku-bucket.sql` 回填里的 8 一致（历史脚本，已下线；
 * 现结构见 db/migration/V1__baseline.sql）。
 * 种子先写 sku.stock，再按同一口径把它平均拆到 N 个桶（余数进 0 号桶）——
 * 拆的是同一份库存，SUM(桶) == sku.stock，不是第二份库存。
 */
const BUCKET_COUNT = 8

// ---------------------------------------------------------------------------
const i18n = JSON.parse(readFileSync(I18N_FILE, 'utf8'))
const untranslated = []
const t = (kind, key, fallback) => {
  const hit = i18n[kind]?.[key]
  if (hit) return hit
  untranslated.push(`${kind}: ${key}`)
  return fallback
}

const esc = (s) => String(s ?? '').replace(/\\/g, '\\\\').replace(/'/g, "''")
const num = (n) => Number(n).toFixed(2)

// ---------------------------------------------------------------------------
// 1. 拉数据
// ---------------------------------------------------------------------------

async function fetchProducts() {
  const res = await fetch('https://dummyjson.com/products?limit=194&select=id,title,description,category,brand,price,thumbnail,images', {
    signal: AbortSignal.timeout(30_000)
  })
  if (!res.ok) throw new Error(`DummyJSON 请求失败：${res.status}`)
  const all = (await res.json()).products
  const byId = new Map(all.map((p) => [p.id, p]))
  const picked = PRODUCT_IDS.map((id) => byId.get(id)).filter(Boolean)
  if (picked.length !== PRODUCT_IDS.length) {
    const missing = PRODUCT_IDS.filter((id) => !byId.has(id))
    console.warn(`! DummyJSON 里缺少这些 id：${missing.join(',')}`)
  }
  return picked
}

// ---------------------------------------------------------------------------
// 2. 图片上传（走后端真实上传接口）
// ---------------------------------------------------------------------------

let adminToken = null

// admin 密码不再硬编码：ADMIN_PASSWORD 环境变量优先，其次读密钥文件；都拿不到就快速失败。
const ADMIN_SECRET_FILE = process.env.ADMIN_PASSWORD_FILE || '/root/.qiqi-secrets/admin.txt'
function readAdminPassword() {
  const fromEnv = process.env.ADMIN_PASSWORD
  if (fromEnv) return fromEnv
  try {
    const pw = readFileSync(ADMIN_SECRET_FILE, 'utf8').trim()
    if (pw) return pw
  } catch {
    // 落到下面统一报错
  }
  console.error(`❌ 缺少 admin 密码（未设置 ADMIN_PASSWORD，且读不到 ${ADMIN_SECRET_FILE}）`)
  process.exit(1)
}

async function login() {
  const res = await fetch(`${API_BASE}/admin/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: readAdminPassword() }),
    signal: AbortSignal.timeout(20_000)
  })
  const body = await res.json()
  if (body.code !== 0) throw new Error(`管理员登录失败：${body.message}`)
  adminToken = body.data.accessToken
}

const uploadStats = { ok: 0, failed: 0, skipped: 0 }

async function uploadImage(url) {
  if (NO_UPLOAD) {
    uploadStats.skipped++
    return url
  }
  try {
    const imgRes = await fetch(url, { signal: AbortSignal.timeout(30_000) })
    if (!imgRes.ok) throw new Error(`下载图片失败 ${imgRes.status}`)
    const buf = Buffer.from(await imgRes.arrayBuffer())
    const type = imgRes.headers.get('content-type') || 'image/webp'
    const ext = type.includes('png') ? 'png' : type.includes('jpeg') ? 'jpg' : 'webp'
    const form = new FormData()
    form.append('file', new Blob([buf], { type }), `seed.${ext}`)
    const up = await fetch(`${API_BASE}/admin/upload/image`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${adminToken}` },
      body: form,
      signal: AbortSignal.timeout(40_000)
    })
    const body = await up.json()
    if (body.code !== 0) throw new Error(body.message || `HTTP ${up.status}`)
    uploadStats.ok++
    return body.data.url
  } catch (e) {
    uploadStats.failed++
    console.warn(`! 图片上传失败，保留外链 ${url} —— ${e.message}`)
    return url
  }
}

// ---------------------------------------------------------------------------
// 3. 组装 SQL
// ---------------------------------------------------------------------------

async function main() {
  console.log(`拉取 DummyJSON 商品（${PRODUCT_IDS.length} 个）…`)
  const products = await fetchProducts()

  if (!NO_UPLOAD) {
    console.log(`登录后端 ${API_BASE} 准备上传图片…`)
    await login()
  }

  // ---- 分类树：从商品的真实分类归纳出两层树 ----
  const rootNames = []
  const childBySlug = new Map() // slug -> { id, root, child }
  let catId = 0
  for (const p of products) {
    const full = t('categories', p.category, p.category)
    const [root, child] = full.includes('/') ? full.split('/') : ['其它', full]
    if (!rootNames.includes(root)) rootNames.push(root)
    if (!childBySlug.has(p.category)) childBySlug.set(p.category, { root, child })
  }
  const rootIdOf = new Map(rootNames.map((r) => [r, ++catId]))
  for (const [slug, v] of childBySlug) v.id = ++catId
  const categoryRows = [
    ...rootNames.map((r) => `  (${rootIdOf.get(r)}, 0, '${esc(r)}')`),
    ...[...childBySlug.values()].map((v) => `  (${v.id}, ${rootIdOf.get(v.root)}, '${esc(v.child)}')`)
  ]

  // ---- 商品 / SKU / 相册（图片先传到 MinIO）----
  const productRows = []
  const skuRows = []
  /** sku id -> 总库存（供后面生成分桶行；改动 skuRows 时必须同步改这里） */
  const skuStockById = new Map()
  const skuSpecRows = []
  const galleryRows = []
  const inventoryRows = []
  let skuId = 0
  let specId = 0
  let galleryId = 0
  let invId = 0
  const skusByProduct = new Map()

  for (const [idx, p] of products.entries()) {
    const productId = idx + 1
    const tr = i18n.products?.[String(p.id)]
    if (!tr?.title) untranslated.push(`products: ${p.id} ${p.title}`)
    if (!tr?.description) untranslated.push(`products(description): ${p.id} ${p.title}`)
    const title = tr?.title || p.title
    const brandCn = p.brand ? t('brands', p.brand, p.brand) : ''
    const cat = childBySlug.get(p.category)
    const typeId = CATEGORY_TYPE[p.category] || 1

    // 主图：thumbnail
    const pic = await uploadImage(p.thumbnail)
    // 商品描述：seed-i18n.json 里的中文描述（缺失时回退英文原文并计入未翻译清单）
    const desc = tr?.description || p.description || ''
    productRows.push(`  (${productId}, '${esc(title)}', '${esc(desc)}', ${cat.id}, ${typeId}, 1, '${esc(pic)}')`)

    // 相册：images[]（同时留一份给 SKU 用：同一商品的不同角度/配色图）
    const images = (p.images || []).slice(0, 3)
    const galleryUrls = []
    for (const [i, url] of images.entries()) {
      const galleryUrl = await uploadImage(url)
      galleryUrls.push(galleryUrl)
      galleryRows.push(`  (${++galleryId}, ${productId}, '${esc(galleryUrl)}', ${i + 1})`)
    }

    // SKU：类型两个属性值的笛卡尔积
    const type = TYPES.find((x) => x.id === typeId)
    const [a1, a2] = type.attrs
    const basePrice = Number(p.price)
    const skus = []
    let k = 0
    for (const v1 of a1.values) {
      for (const v2 of a2.values) {
        const id = ++skuId
        const price = basePrice * (1 + 0.05 * k) // 不同组合轻微价差，方便演示
        // 每个 SKU 用不同的图：SKU1=商品主图，SKU2..4=相册里的不同角度图（相册不够就回退主图）
        const skuPic = k === 0 ? pic : (galleryUrls[k - 1] || pic)
        skuRows.push(`  (${id}, ${productId}, ${num(price)}, ${SKU_STOCK}, '${esc(skuPic)}')`)
        skuStockById.set(id, SKU_STOCK)
        skuSpecRows.push(`  (${++specId}, ${id}, ${a1.id}, '${esc(v1)}')`)
        skuSpecRows.push(`  (${++specId}, ${id}, ${a2.id}, '${esc(v2)}')`)
        const wh = SKU_WAREHOUSE_CYCLE[k % SKU_WAREHOUSE_CYCLE.length]
        inventoryRows.push(`  (${++invId}, ${id}, ${wh}, ${SKU_STOCK})`)
        skus.push({ id, price, spec: `${a1.name}:${v1},${a2.name}:${v2}` })
        k++
      }
    }
    skusByProduct.set(productId, skus)
    console.log(`  #${productId} ${title} —— 4 SKU（${brandCn || '无品牌'}）`)
  }

  // ---- 会员 / 管理员 / 字典（BCrypt 哈希直接抄 sql/data.sql 的 123456）----
  const BC = '$2a$10$V1FYkp/NYOZy3z2ZgUW/f.NCGkOcVTPS.AEMu.W3U.pvhKssTDJGS'
  const memberRows = [
    `  (1, 'qiqi',     '${BC}', '七七', '13800000001', 1)`,
    `  (2, 'xiaoming', '${BC}', '小明', '13800000002', 1)`,
    `  (3, 'laowang',  '${BC}', '老王', '13800000003', 0)`
  ]
  const addressRows = [
    `  (1, 1, '七七', '13800000001', '广东省', '深圳市', '南山区', '科技园南路 1 号 301', 1)`,
    `  (2, 1, '七七', '13800000001', '北京市', '北京市', '朝阳区', '建国路 88 号 A 座 1502', 0)`,
    `  (3, 2, '小明', '13800000002', '上海市', '上海市', '浦东新区', '张江路 200 号', 1)`
  ]
  const adminRows = [`  (1, 'admin', '${BC}', '超级管理员', 'admin@qiqi-mall.local', 1)`]
  const reasonRows = ["  (1, '质量问题')", "  (2, '七天无理由')", "  (3, '发错货')", "  (4, '不想要了')"]
  const warehouseRows = WAREHOUSES.map((w) => `  (${w.id}, '${esc(w.name)}', 1)`)

  // ---- 演示订单：让后台列表与数据概览有真实感（不影响 e2e，e2e 都按订单号定位）----
  //   近 7 天每天都有成交，状态覆盖 已付款/已发货/已完成/待审核/待发货，
  //   商品取前几个（销量排行榜才有意义），金额按 SKU 价格 × 数量算。
  const orderRows = []
  const orderItemRows = []
  const paymentRows = []
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const dayOf = (offset) => {
    const d = new Date(now.getTime() - offset * 24 * 3600 * 1000)
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  }
  // [天数偏移, 状态, 会员id, 商品序号(0基), 件数]
  const demoPlan = [
    [0, 7, 1, 0, 2], [0, 8, 2, 1, 1], [0, 7, 3, 2, 1], [0, 9, 1, 5, 1],
    [1, 2, 2, 0, 1], [1, 3, 1, 3, 2],
    [2, 3, 3, 0, 1],
    [3, 2, 1, 4, 1], [3, 1, 2, 6, 1],
    [4, 3, 2, 0, 3],
    [5, 3, 3, 7, 1], [5, 2, 1, 1, 2],
    [6, 3, 1, 0, 1], [6, 3, 2, 8, 1]
  ]
  let orderId = 0
  let itemId = 0
  let payId = 0
  const receiverOf = { 1: ['七七', '13800000001', '广东省深圳市南山区科技园南路 1 号 301'],
                       2: ['小明', '13800000002', '上海市上海市浦东新区张江路 200 号'],
                       3: ['老王', '13800000003', '北京市北京市朝阳区建国路 88 号'] }
  for (const [offset, status, memberId, productIdx, qty] of demoPlan) {
    orderId++
    const sku = skusByProduct.get(productIdx + 1)[0]
    const total = Number(sku.price) * qty
    const sn = `QQ${dayOf(offset).replace(/-/g, '')}${String(1000 + orderId)}`
    const [rName, rPhone, rAddr] = receiverOf[memberId]
    const payTime = status === 0 ? 'NULL' : `'${dayOf(offset)} ${pad(9 + (orderId % 9))}:${pad((orderId * 7) % 60)}:00'`
    orderRows.push(`  (${orderId}, '${sn}', ${memberId}, ${num(total)}, ${status}, 1, ${payTime}, '${esc(rName)}', '${rPhone}', '${esc(rAddr)}')`)
    orderItemRows.push(`  (${++itemId}, ${orderId}, ${productIdx + 1}, ${sku.id}, '${esc(i18n.products[String(products[productIdx].id)]?.title || products[productIdx].title)}', ${num(sku.price)}, ${qty})`)
    if (status !== 0) {
      paymentRows.push(`  (${++payId}, '${sn}', ${num(total)}, 1, 1, 'MOCKPAY${dayOf(offset).replace(/-/g, '')}${String(1000 + orderId)}')`)
    }
  }

  // ---- 库存告警：留几个低库存 SKU，让概览页的告警列表有内容 ----
  for (const [skuIdx, stock] of [[9, 6], [17, 3], [33, 8]]) {
    const row = skuRows[skuIdx]
    if (row) {
      skuRows[skuIdx] = row.replace(/, \d+, '/, `, ${stock}, '`)
      skuStockById.set(skuIdx + 1, stock)   // sku id 从 1 连续编号，与 skuRows 下标一一对应
    }
  }

  // ---- 库存分桶行：sku_stock 平均拆成 BUCKET_COUNT 个桶（余数进 0 号桶）----
  // 与 `sql/_rollback/migrate-sku-bucket.sql` 的回填口径完全一致（同一份库存的物理拆分；该脚本已下线）。
  // 为什么种子必须自己写：sku_bucket 是权威库存，reset 库时只 TRUNCATE 不插入的话，
  // 每个 SKU 都会变成「0 库存」—— 所有下单都会报库存不足，而且看不出原因。
  const bucketNoOf = (stock, bucketNo) => {
    const base = Math.floor(stock / BUCKET_COUNT)
    return bucketNo === 0 ? base + (stock - base * BUCKET_COUNT) : base
  }
  const skuBucketRows = []
  for (const [id, stock] of [...skuStockById.entries()].sort((a, b) => a[0] - b[0])) {
    for (let bucketNo = 0; bucketNo < BUCKET_COUNT; bucketNo++) {
      skuBucketRows.push(`  (${id}, ${bucketNo}, ${bucketNoOf(stock, bucketNo)})`)
    }
  }

  // ---- 拼 SQL ----
  // ⚠️ 本文件里**不许出现任何 `USE <库名>;` / `CREATE DATABASE`**：库名必须由调用方给
  //    （`mysql <库名> < sql/testdb/02-seed.sql`）。曾经这里写死 `USE qiqi_mall_test;`，
  //    结果 2026-11-13 有两次事故就是这么来的：
  //      ① 调用方以为"传了库名就安全"，实际 seed 里的 USE 覆盖了它 → 种子灌进了 qiqi_mall_test
  //         而不是目标库，目标库留空、真库被 TRUNCATE 重灌；
  //      ② 脚本以为自己换库名成功了（sed 只是补救），下一个读者根本看不出危险在哪。
  //    写死库名等于给"重置库"这类操作埋一颗只会响一次的雷，必须在源头拆掉。
  const tables = [
    'cart', 'member_address', 'return_apply', 'order_item', '`order`', 'stock_ledger', 'return_reason',
    'order_setting', 'payment_record', 'delivery_logistics', 'delivery_item', 'delivery_order',
    'inventory', 'warehouse', 'sku_bucket', 'sku_spec', 'product_gallery', 'sku', 'product', 'attribute',
    'product_type', 'category', 'member', 'admin'
  ]
  const sql = `-- 七七商城 测试库种子数据（可用库：qiqi_mall_test / qiqi_mall_e2e_*）
-- 由 scripts/fetch-seed-data.mjs 自动生成，不要手改：改脚本或 sql/testdb/seed-i18n.json 后重跑
--   商品数据来源：DummyJSON（https://dummyjson.com），图片经 POST /admin/upload/image 落 MinIO
--   生成时间：${new Date().toISOString()}
-- 幂等策略：SET FOREIGN_KEY_CHECKS=0 → 逐表 TRUNCATE → 按固定 id 插入 → 恢复外键检查（可反复重跑）
-- ⚠️ 库名不写在本文件里（没有 USE / CREATE DATABASE），必须由调用方指定：
--       mysql --default-character-set=utf8mb4 -uroot -p <目标库> < sql/testdb/02-seed.sql
--    写死库名会让"重置某个库"变成"重置它写死的那个库"（2026-11-13 两次事故的根因）。
-- 口令说明：本文件里 admin 的哈希是「演示弱口令」占位，只为让种子自己能登录，不含真实口令。
--   → 测试库复位后由 scripts/set-admin-password.sh 从 /root/.qiqi-secrets/admin.txt 覆盖；
--   → e2e 独立库走 scripts/reset-e2e-db.sh，不跑那一步，仍用本行的占位哈希。
--   换口令请改密钥文件（chmod 600），不要把真实哈希写进本文件（它被 git 跟踪、且会被重生成覆盖）。

SET FOREIGN_KEY_CHECKS = 0;
${tables.map((x) => `TRUNCATE TABLE ${x};`).join('\n')}
SET FOREIGN_KEY_CHECKS = 1;

-- ============ 用户 ============
INSERT INTO member (id, username, password, nickname, phone, status) VALUES
${memberRows.join(',\n')};

INSERT INTO member_address (id, member_id, name, phone, province, city, region, detail, default_status) VALUES
${addressRows.join(',\n')};

INSERT INTO admin (id, username, password, nickname, email, status) VALUES
${adminRows.join(',\n')};

-- ============ 商品分类（两层树，${rootNames.length} 个根）============
INSERT INTO category (id, parent_id, name) VALUES
${categoryRows.join(',\n')};

-- ============ 商品类型 / 属性 ============
INSERT INTO product_type (id, name) VALUES
${TYPES.map((x) => `  (${x.id}, '${esc(x.name)}')`).join(',\n')};

INSERT INTO attribute (id, type_id, name, is_filter) VALUES
${TYPES.flatMap((x) => x.attrs.map((a) => `  (${a.id}, ${x.id}, '${esc(a.name)}', 1)`)).join(',\n')};

-- ============ 商品 / SKU / 相册 ============
INSERT INTO product (id, name, description, category_id, type_id, publish_status, pic) VALUES
${productRows.join(',\n')};

INSERT INTO sku (id, product_id, price, stock, pic) VALUES
${skuRows.join(',\n')};

-- 库存分桶（权威库存；sku.stock 是它的展示缓存，由后端周期任务回写）
-- 拆分口径：floor(stock/8) 每桶 + 余数进 0 号桶 ⇒ SUM(stock) 严格等于上面的 sku.stock
INSERT INTO sku_bucket (sku_id, bucket_no, stock) VALUES
${skuBucketRows.join(',\n')};

INSERT INTO sku_spec (id, sku_id, attribute_id, value) VALUES
${skuSpecRows.join(',\n')};

INSERT INTO product_gallery (id, product_id, url, sort) VALUES
${galleryRows.join(',\n')};

-- ============ 仓库 / 分仓库存（SKU1→深圳仓、SKU2→杭州仓、SKU3→成都仓、SKU4→深圳仓）============
INSERT INTO warehouse (id, name, status) VALUES
${warehouseRows.join(',\n')};

INSERT INTO inventory (id, sku_id, warehouse_id, stock) VALUES
${inventoryRows.join(',\n')};

-- ============ 字典 ============
INSERT INTO return_reason (id, name) VALUES
${reasonRows.join(',\n')};

INSERT INTO order_setting (id, auto_confirm_days, auto_close_minutes) VALUES
  (1, 7, 30);

-- ============ 示例订单（让后台列表不空）============
INSERT INTO \`order\` (id, order_sn, member_id, total_amount, status, pay_type, payment_time, receiver_name, receiver_phone, receiver_address) VALUES
${orderRows.join(',\n')};

INSERT INTO order_item (id, order_id, product_id, product_sku_id, product_name, product_price, product_quantity) VALUES
${orderItemRows.join(',\n')};

INSERT INTO payment_record (id, order_sn, amount, type, channel, trade_no) VALUES
${paymentRows.join(',\n')};
`

  mkdirSync(dirname(OUT_FILE), { recursive: true })
  writeFileSync(OUT_FILE, sql)

  // ---- 报告 ----
  const lines = sql.split('\n').length
  console.log('\n=== 生成完成 ===')
  console.log(`输出：sql/testdb/02-seed.sql（${lines} 行，${(Buffer.byteLength(sql) / 1024).toFixed(1)} KB）`)
  console.log(`分类 ${rootNames.length} 个根 + ${childBySlug.size} 个子类；类型 ${TYPES.length} 个、属性 ${TYPES.length * 2} 个`)
  console.log(`商品 ${productRows.length} 个；SKU ${skuRows.length} 个；分桶 ${skuBucketRows.length} 行（每 SKU ${BUCKET_COUNT} 桶）；sku_spec ${skuSpecRows.length} 行；相册 ${galleryRows.length} 张；分仓库存 ${inventoryRows.length} 行`)
  console.log(`图片：上传成功 ${uploadStats.ok}，失败 ${uploadStats.failed}，跳过(--no-upload) ${uploadStats.skipped}`)
  console.log(`类型/属性/规格值全部中文；商品标题与描述走 seed-i18n.json`)
  console.log(`\n未翻译清单（${new Set(untranslated).size} 项）：`)
  const uniq = [...new Set(untranslated)]
  console.log(uniq.length ? uniq.map((x) => '  - ' + x).join('\n') : '  （无，全部命中 seed-i18n.json）')
}

main().catch((e) => {
  console.error('✗ 生成失败：', e.message)
  process.exit(1)
})
