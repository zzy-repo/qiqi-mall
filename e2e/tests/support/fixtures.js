/**
 * 测试数据工厂 —— 让用例里出现的数据「像真的」。
 *
 * 背景：以前商品叫 `E2E维护商品97168222`、主图是 writeTempPng() 画的纯色方块，
 * 录进演示视频里就是一行乱码 + 一块蓝方块。这里把「显示给人看的数据」和
 * 「保证唯一性的技术标识」分开：
 *
 *   · 显示的（商品名、昵称、收件人、地址、仓库名、分类/属性名、图片）→ 一律取自下面的池子
 *   · 不显示的（登录名、内部追踪号）→ 才允许带时间戳
 *
 * 三条硬规则（改 spec 时自查）：
 *   1. writeTempPng() 生成的纯色图**只能**用于上传边界测试（415 / 413 这类），
 *      商品主图/相册/SKU 图必须用 realImages() / realGalleryImages() / realSkuImages()（MinIO 上的真实种子图）。
 *   2. 界面上不得出现 `E2E`、时间戳、UUID。唯一性放到不显示的字段（登录名 / 内部备注）。
 *   3. 断言里可以用时间戳做唯一标识，但不许靠「界面显示名含时间戳」来定位元素 ——
 *      用 data-testid（行锚点 `*-row-<id>`）或先用接口查出 id 再定位。
 *
 * 唯一性与「重名」的处理：
 *   · 商品/分类的名字取自小池子，多轮跑会重名 → 用 `newestByName()` 取「最新一条」，
 *     行内定位优先用 `product-row-<id>` / `category-row-<id>` 这类行锚点，不靠名字过滤。
 *   · 仓库名后端允许重名，但列表是按名字定位的 → 名字里带一个像真实库位编号的后缀（B482 这种），
 *     既不重复也仍然「像真的」。
 */

// ---------------------------------------------------------------------------
// 时间戳 / 唯一键
// ---------------------------------------------------------------------------

/** 短唯一后缀（只用于不显示的字段，不用于界面可见文案） */
export const stamp = () => Date.now().toString(36).slice(-5)

/** 确定性取：同一个 seed 永远取到同一条（用例内保持稳定，不同轮次又会变） */
export function pick(arr, seed) {
  if (!arr.length) throw new Error('pick(): 池子不能为空')
  const s = String(seed ?? '')
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 100000
  return arr[h % arr.length]
}

/** 只用于唯一性、不显示的键（登录名/内部备注用） */
export const uniqueKey = (base) => `${base}_${stamp()}`

// ---------------------------------------------------------------------------
// 真实图片池（MinIO 上的种子图，都是真照片，不是色块）
// ---------------------------------------------------------------------------

const IMG_BASE = '/qiqi-mall/seed'
const productImg = (file) => `${IMG_BASE}/product/${file}.webp`

/**
 * 可用的种子图清单（**已在浏览器同源下逐个验证 HTTP 200**，2026-11-11 复核）：
 *   封面：1..5            相册：1-1 1-2 1-3 2-1 4-1 5-1          SKU：sku/1..9
 * 注意：库里 seed 数据引用了 6..11 的封面和更多相册图，但这些对象在 MinIO 上并不存在
 * （直连 8081 会返回 200，但那是网关的 JSON 兜底，浏览器里 404 → el-image 显示「无法加载」）。
 * 所以这里的池子只放确认存在的图，别再加没验证过的路径。
 */
export const realImages = ['1-cover', '2-cover', '3-cover', '4-cover', '5-cover'].map(productImg)

/** 每张封面配套的相册图（都取自上面验证过的清单） */
export const realGalleryImageSets = [
  { cover: productImg('1-cover'), gallery: [productImg('1-1'), productImg('1-2'), productImg('1-3')] },
  { cover: productImg('2-cover'), gallery: [productImg('2-1'), productImg('4-1'), productImg('5-1')] },
  { cover: productImg('4-cover'), gallery: [productImg('4-1'), productImg('5-1'), productImg('2-1')] },
  { cover: productImg('5-cover'), gallery: [productImg('5-1'), productImg('1-2'), productImg('1-3')] }
]

/** 9 张 SKU 图 */
export const realSkuImageList = Array.from({ length: 9 }, (_, i) => `${IMG_BASE}/sku/${i + 1}.webp`)

/** 取一张真实商品封面 */
export const realImage = (seed) => pick(realImages, seed)

/** 取一组「封面 + 相册」的真实图（相册条目数固定 3 张） */
export const realGalleryImages = (seed) => pick(realGalleryImageSets, seed)

/** 取一张真实 SKU 图 */
export const realSkuImages = (seed) => pick(realSkuImageList, seed)

// ---------------------------------------------------------------------------
// 商品名：自然中文商品名 + 款式限定，读起来像电商后台里真实存在的商品
// ---------------------------------------------------------------------------

export const productNames = [
  '手冲咖啡滤杯', '亚麻抱枕套', '原木收纳盒', '陶瓷马克杯', '双层玻璃杯',
  '羊毛围巾', '帆布托特包', '香薰蜡烛', '不锈钢保温壶', '硅胶烘焙垫',
  '折叠露营椅', '天然乳胶枕', '竹编果盘', '磨砂玻璃花瓶', '手作陶土花盆',
  '纯棉浴巾', '厚底棉拖鞋', '胡桃木餐具垫', '迷你桌面加湿器', '编织收纳篮',
  '椴木砧板', '低款搪瓷碗'
]

/** 款式限定词（和商品名组合，避免多轮跑重名，同时保持自然） */
export const productEditions = ['经典款', '轻奢款', '家用款', '礼盒装', '升级款', '联名款', '旅行装', '家用两件套']

/** 生成一个「好看的」商品名，如「手冲咖啡滤杯 · 礼盒装」 */
export function productNameOf(seed) {
  return `${pick(productNames, seed)} · ${pick(productEditions, `${seed}-ed`)}`
}

/** 商品描述（展示在 C 端详情页，必须像人写的） */
export const productDescriptions = [
  '选用天然材质，手感细腻；日常使用不易变形，适合家用与送礼。',
  '手工打磨边缘，触感温润；容量适中，清洗方便，可进洗碗机。',
  '简约百搭的日常款，做工扎实；适合厨房、书房与办公桌。',
  '轻量便携，收纳不占地；出行与居家都合用。'
]
export const productDescriptionOf = (seed) => pick(productDescriptions, seed)

// ---------------------------------------------------------------------------
// 会员 / 地址 / 收件人
// ---------------------------------------------------------------------------

export const memberNicks = [
  '林晚', '沈知遥', '程叙白', '苏念', '顾清和', '叶其深',
  '许星野', '温以宁', '陆见微', '宋知遥', '周予安', '白露',
  '孟云舟', '乔之南'
]
export const memberNickOf = (seed) => pick(memberNicks, seed)

/** 地址簿（买家侧地址页 / 结算页收货信息都用它，字段与表单一一对应） */
export const addressBook = [
  { name: '林晚', phone: '13800001111', province: '浙江省', city: '杭州市', region: '西湖区', detail: '文三路 123 号 5 幢 201' },
  { name: '沈知遥', phone: '13900002222', province: '上海市', city: '上海市', region: '浦东新区', detail: '张江路 200 号 8 楼 802' },
  { name: '程叙白', phone: '13700003333', province: '广东省', city: '深圳市', region: '南山区', detail: '科苑南路 66 号 A 座 1503' },
  { name: '苏念', phone: '13600004444', province: '北京市', city: '北京市', region: '朝阳区', detail: '建国路 88 号 3 单元 1102' },
  { name: '顾清和', phone: '13500005555', province: '江苏省', city: '南京市', region: '鼓楼区', detail: '中山北路 45 号 6 幢 302' },
  { name: '温以宁', phone: '13400006666', province: '四川省', city: '成都市', region: '武侯区', detail: '天府大道中段 1 号 2 栋 1808' }
]
export const addressOf = (seed) => pick(addressBook, seed)

/** 结算/下单时的收货人（真实姓名 + 真实地址） */
export const receiverOf = (seed) => {
  const a = addressOf(seed)
  return { receiverName: a.name, receiverPhone: a.phone, receiverAddress: `${a.province}${a.city}${a.region}${a.detail}` }
}

// ---------------------------------------------------------------------------
// 仓库 / 管理员 / 分类 / 属性
// ---------------------------------------------------------------------------

/** 仓库名：好听 + 带一个像库位编号的后缀，保证多轮跑不重名 */
export const warehouseBases = ['杭州云栖仓', '北京亦庄仓', '广州南沙仓', '成都双流仓', '武汉东西湖仓', '西安沣东仓']
/**
 * @param salt 同一次用例里要造多个仓库时传不同的 salt，保证三个名字两两不同
 *             （只用 seed 时 base / renamed / dup 的数字来源相同，会撞成同名 → 按文本定位会取错行）
 */
export function warehouseNameOf(seed, salt = '') {
  const key = `${seed}#${salt}`
  let h = 0
  for (let i = 0; i < key.length; i++) h = (h * 131 + key.charCodeAt(i)) % 100000
  return `${pick(warehouseBases, key)}（B${(h % 900) + 100}）`
}

/** 管理员昵称（列表里的「昵称」列会显示） */
export const adminNicks = ['陈运营', '许客服', '周仓管', '孙财务']
export const adminNickOf = (seed) => pick(adminNicks, seed)

/** 商品分类（展示在分类树与 C 端筛选项） */
export const categoryNames = ['家居日用', '文具办公', '户外运动', '宠物生活', '餐厨用品', '收纳整理', '织物家纺', '绿植花器']
export const categoryOf = (seed) => pick(categoryNames, seed)

/** 商品类型（展示在类型列表） */
export const typeNames = ['家居好物', '服饰配件', '数码配件', '餐厨好物', '户外装备', '母婴用品', '宠物用品', '办公文具']
export const typeOf = (seed) => pick(typeNames, seed)

/** 属性名（展示在属性列表与 SKU 规格上） */
export const attributeNames = ['颜色', '尺码', '材质', '容量', '风格', '口味', '净含量', '工艺']
export const attributeOf = (seed) => pick(attributeNames, seed)

/** 属性值（展示在 SKU 规格文本里） */
export const attributeValues = ['曜石黑', '雾霾蓝', '暖石白', '原木色', '深灰', '米杏']
export const attributeValueOf = (seed) => pick(attributeValues, seed)

/** 退货说明（买家侧填写，会出现在退货申请列表） */
export const returnDescriptions = [
  '收到时外包装有压痕，杯口有一道细划痕，申请退货。',
  '颜色和页面展示差得比较多，想换成米杏色。',
  '尺寸比预期大，放不进柜子，申请退货。'
]
export const returnDescriptionOf = (seed) => pick(returnDescriptions, seed)

// ---------------------------------------------------------------------------
// 断言用：从列表里取「最新一条」
// ---------------------------------------------------------------------------

/**
 * 造一个「好看且一定不重名」的显示名。
 *
 * 分类 / 类型的用例要断言「改名后旧名字不存在」，所以名字必须全局唯一；
 * 又不能塞时间戳（视频里难看）。做法：用自然基名 + 「第 N 期」，N 取库里已有同名基名的最大期数 +1，
 * 于是「家居日用（第 2 期）」这种既像人起的名字，又不会和上一轮的撞。
 *
 * @param base 自然基名，如 '家居日用'
 * @param existingNames 现有名字数组（如分类/类型列表的 name）
 */
export function uniqueDisplayName(base, existingNames = []) {
  const re = new RegExp(`^${base}（第 (\\d+) 期）$`)
  let max = 0
  for (const name of existingNames) {
    const m = re.exec(String(name))
    if (m) max = Math.max(max, Number(m[1]))
  }
  return `${base}（第 ${max + 1} 期）`
}

/**
 * 同名数据在库里会累积（商品/分类等名字取自小池子），
 * 断言要针对「本次刚创建的那条」，所以一律取 id 最大的一条。
 */
export function newestByName(list, name) {
  return (list || [])
    .filter((r) => r.name === name)
    .sort((a, b) => Number(b.id) - Number(a.id))[0]
}

/** 取最新一条（不按名字过滤时用） */
export function newestOf(list) {
  return (list || []).slice().sort((a, b) => Number(b.id) - Number(a.id))[0]
}
