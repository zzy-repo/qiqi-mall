#!/usr/bin/env node
/**
 * t5-audit-walkthrough.mjs —— 「一条不命中的单」三分钟走查：截图 + 录屏（同一套动作，两种产物）
 *
 *   node e2e/scripts/t5-audit-walkthrough.mjs --mode=shots     # 出 5 张 1960×1120 截图（干净，无字幕）
 *   node e2e/scripts/t5-audit-walkthrough.mjs --mode=video     # 出一段 ≤3 分钟 mp4（带字幕，一镜到底）
 *
 * 为什么两种模式跑同一份代码：截图与录屏必须证明**同一件事**。若分两个脚本各写一遍点击顺序，
 * 迟早出现「截图里是 A 单的第三层、视频里是 B 单」这种对不上的证据。
 *
 * 五步（与任务书 ② 一一对应，顺序也是视频里的顺序）：
 *   1 队列行：该行挂「无策略命中」小标 + 原因列当场说「最像哪条、差什么」（不点抽屉）
 *   2 点开抽屉第一层：结论 + 一句人话 + 最接近那条 + 灰字「另有 N 条不适用（渠道不符…）」
 *   3 展开第二层：逐条不适用策略 + 一句为什么
 *   4 展开第三层：完整矩阵
 *   5 按标筛选「无策略命中」：行数减少，且每行都真挂该标（逐行读 data-tag-codes 核）
 *
 * 字幕：为什么是**页面上叠一条**而不是配音
 *   这台机器没有录音设备，也没有配音链路；「口播」在这里落成**屏幕上的一行字**（录进画面）。
 *   字幕是录屏模式专有的：截图模式不注入，交付的图里就不会多出一条谁也没要求的横条。
 *
 * 目标站点：默认打**公网真站** https://admin.rose7.cn（= 容器栈 5176 背后的同一个库 qiqi_mall_test、
 * 同一份前端产物，已验证 order-audit chunk 哈希一致）。要打容器栈就 --base=http://127.0.0.1:5176，
 * 区别只是右上角多一个「测试环境」徽标。
 *
 * 口令：ADMIN_PASSWORD 或 /root/.qiqi-secrets/admin.txt（与 demo-run.sh / prepare-audit-demo-queue.mjs 同源）。
 * 退出码：0 = 五步都走完且断言全过；1 = 某一步没成立（截图/视频就没资格当证据）。
 */
import { chromium } from 'playwright'
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync } from 'node:fs'
import { join, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'

const E2E_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const REPO = resolve(E2E_DIR, '..')

const argv = process.argv.slice(2)
const arg = (name, dflt = null) => {
  const hit = argv.find((a) => a === `--${name}` || a.startsWith(`--${name}=`))
  if (!hit) return dflt
  const eq = hit.indexOf('=')
  return eq === -1 ? true : hit.slice(eq + 1)
}

const BASE = String(arg('base', 'https://admin.rose7.cn')).replace(/\/+$/, '')
const MODE = String(arg('mode', 'shots'))
const SCENE_REL = '业务/订单-自动审核/不命中单三分钟走查'
const SHOT_DIR = resolve(REPO, String(arg('shots-dir', `docs/flows/02-业务场景/${SCENE_REL}`)))
const VIDEO_OUT = resolve(REPO, String(arg('video', `e2e/report/videos/${SCENE_REL}/1-三分钟走查.mp4`)))
const ORDER_SN = String(arg('order', 'QT-1003'))
const CHROME = process.env.CHROME_PATH
  || '/root/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome'
const VIEWPORT = { width: 1960, height: 1120 }     // 与 playwright.config.js 的 DEMO_VIEWPORT 同一口径
const SLOW_MO = MODE === 'video' ? 350 : 60        // 录屏要慢到人眼跟得上；截图不用等
/**
 * 录屏节奏（毫秒）。**录屏不是跑测试**：断言只要 16 秒就跑完了，但片子得让人看清、
 * 听（读）懂每一步在说什么。实测：全默认节奏只出 16.8 秒的片子，五步一闪而过，等于没演。
 * 这里把「读完字幕」与「看清这一屏」折成显式停顿，目标 90~150 秒（任务上限 3 分钟）。
 * 截图模式不需要停顿（要的是干净的一帧），所以全是 0。
 */
const PACE = MODE === 'video'
  ? { caption: 4200, settle: 2600, action: 1400, read: 5200 }
  : { caption: 0, settle: 300, action: 200, read: 200 }
const hold = (page, ms) => page.waitForTimeout(ms)
const SECRET_FILE = process.env.ADMIN_PASSWORD_FILE || '/root/.qiqi-secrets/admin.txt'

if (!['shots', 'video'].includes(MODE)) {
  console.error(`✗ --mode 只支持 shots / video（收到 ${MODE}）`)
  process.exit(2)
}

const fail = (msg) => {
  console.error(`✗ ${msg}`)
  process.exit(1)
}
const log = (...a) => console.log(...a)

function adminPassword() {
  if (process.env.ADMIN_PASSWORD) return process.env.ADMIN_PASSWORD
  if (existsSync(SECRET_FILE)) {
    const s = readFileSync(SECRET_FILE, 'utf8').trim()
    if (s) return s
  }
  fail(`拿不到 admin 口令：设 ADMIN_PASSWORD，或写入 ${SECRET_FILE}`)
  return null
}

/* ------------------------------------------------------------------ 字幕 */

/**
 * 在页面右下角叠一条字幕（只用于录屏）。
 *
 * 为什么钉在 `position: fixed; bottom` 而不是顶部：审核队列表与抽屉内容的重点都在上半屏，
 * 顶部会压住「判定」徽标与第一层那句话 —— 正是这五步要给人看的东西。
 */
async function injectCaption(page) {
  await page.evaluate(() => {
    if (document.getElementById('t5-caption')) return
    const d = document.createElement('div')
    d.id = 't5-caption'
    d.style.cssText = [
      'position:fixed', 'left:0', 'right:0', 'bottom:0', 'z-index:99999',
      'background:rgba(17,24,39,0.88)', 'color:#fff', 'font-size:26px', 'line-height:1.5',
      'padding:14px 28px', 'font-family:"Noto Sans CJK SC","Microsoft YaHei",sans-serif',
      'border-top:3px solid #409eff', 'min-height:56px', 'box-sizing:border-box',
    ].join(';')
    document.body.appendChild(d)
  })
}
const caption = async (page, text) => {
  if (MODE !== 'video') return
  await injectCaption(page)
  await page.evaluate((t) => {
    const el = document.getElementById('t5-caption')
    if (el) el.textContent = t
  }, text)
  await page.waitForTimeout(PACE.caption)   // 让人把这句话读完再动手
}

/* ------------------------------------------------------------------ 队列页小工具 */

/**
 * 队列行定位。
 *
 * ⚠️ 一定要把选择器**钉在队列表自己身上**（`audit-queue-table` 之内）：
 * 「判定依据」抽屉里的第二层/第三层也是 el-table 渲染的，抽屉关掉后 DOM 还在
 * （el-drawer 不是 destroy-on-close）。整页 `.el-table__body tr` 一数，就会把抽屉里那三张表
 * 也算成队列行 —— 实测踩到：筛完明明是 4 行，却数出 12 行（4 + 2 + 3 + 3），
 * 于是「行数减少」这条断言假红。这类错误的可怕之处是它可能反向假绿（数大了）。
 */
const QUEUE_TABLE = (page) => page.getByTestId('audit-queue-table')
const QUEUE_ROW = (page, sn) =>
  QUEUE_TABLE(page).locator('.el-table__body tr').filter({ has: page.getByTestId(`audit-queue-row-${sn}`) })

const queueRowCount = (page) => QUEUE_TABLE(page).locator('[data-testid^="audit-queue-row-"]').count()

/** 一页队列里每行的「单号 + 挂着的标码」（标码读行内 chips 的 data-tag-codes，不是猜文案） */
async function queueFacts(page) {
  return page.evaluate(() => {
    const root = document.querySelector('[data-testid="audit-queue-table"]')
    if (!root) return []
    const rows = [...root.querySelectorAll('.el-table__body tr')]
    return rows.map((tr) => {
      const sn = tr.querySelector('[data-testid^="audit-queue-row-"]')?.getAttribute('data-testid')?.replace('audit-queue-row-', '') || ''
      const chips = tr.querySelector('[data-tag-codes]')
      const codes = (chips?.getAttribute('data-tag-codes') || '').split(',').filter(Boolean)
      return { sn, codes }
    }).filter((r) => r.sn)
  })
}

/* ------------------------------------------------------------------ 主流程 */

const PW = adminPassword()
const shotsMade = []
const videoTmp = MODE === 'video' ? join('/tmp', `t5-walkthrough-${Date.now()}`) : null

mkdirSync(SHOT_DIR, { recursive: true })
if (MODE === 'video') mkdirSync(videoTmp, { recursive: true })

const browser = await chromium.launch({
  executablePath: CHROME,
  args: ['--no-sandbox', '--disable-dev-shm-usage'],
  slowMo: SLOW_MO,
})
const context = await browser.newContext({
  viewport: VIEWPORT,
  ...(MODE === 'video' ? { recordVideo: { dir: videoTmp, size: VIEWPORT } } : {}),
})
const page = await context.newPage()

/** 截一张图（只 shots 模式落盘；video 模式的画面由录制负责） */
async function shoot(name) {
  if (MODE !== 'shots') return
  const p = join(SHOT_DIR, `${name}.png`)
  await page.screenshot({ path: p })      // 不给 fullPage：要的就是 1960×1120 这一屏
  const st = statSync(p)
  shotsMade.push({ name, path: p, bytes: st.size })
  log(`   📷 ${name}.png  (${(st.size / 1024).toFixed(0)} KB)`)
}

try {
  /* ---------- 登录 ---------- */
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await page.getByTestId('login-form-username').fill('admin')
  await page.getByTestId('login-form-password').fill(PW)
  await page.getByTestId('login-form-submit').click()
  await page.waitForURL((u) => !String(u).includes('/login'), { timeout: 30_000 })
  log(`▶ 已登录 ${BASE}`)

  /* ---------- 第 1 步：队列行 ---------- */
  await page.goto(`${BASE}/order-audit`, { waitUntil: 'domcontentloaded' })
  await page.getByTestId('audit-queue-table').waitFor({ state: 'visible', timeout: 30_000 })
  await page.waitForTimeout(1200)

  const row = QUEUE_ROW(page, ORDER_SN)
  await row.waitFor({ state: 'visible', timeout: 20_000 })
  const rowCountBefore = await queueRowCount(page)
  const closestLine = (await row.getByTestId('audit-row-closest').innerText()).trim()
  const badge = (await row.getByTestId('audit-row-badge').getAttribute('data-result')) || ''
  const rowCodes = (await row.locator('[data-tag-codes]').first().getAttribute('data-tag-codes') || '')
    .split(',').filter(Boolean)
  if (badge !== 'NO_MATCH') fail(`第 1 步：${ORDER_SN} 的判定徽标是 ${badge}，不是 NO_MATCH`)
  if (!rowCodes.includes('NO_POLICY_MATCH')) fail(`第 1 步：${ORDER_SN} 行上没挂 NO_POLICY_MATCH（挂了 ${rowCodes.join(',')}）`)
  if (!closestLine) fail('第 1 步：行内没有「最像哪条、差什么」那一行（audit-row-closest 为空）')
  log(`▶ 第 1 步：队列 ${rowCountBefore} 行；${ORDER_SN} 挂 ${rowCodes.join(',')}；行内一句话「${closestLine}」`)

  await caption(page, '第 1 步 · 队列这一行就写着：无策略命中，最像哪条、还差什么 —— 不用点开抽屉')
  await row.scrollIntoViewIfNeeded()
  await hold(page, PACE.settle)
  // 慢慢把鼠标移到这行上（不是「悬停提示」，是让人眼睛跟得上这一屏改变）
  const box = await row.boundingBox()
  if (box) {
    await page.mouse.move(box.x + 120, box.y - 120)
    await page.mouse.move(box.x + 180, box.y + 40, { steps: 12 })
  }
  await hold(page, PACE.read)
  await shoot('1-队列行说不命中')

  /* ---------- 第 2 步：抽屉第一层 ---------- */
  await caption(page, '第 2 步 · 点开「判定依据」：结论 + 一句人话 + 最接近那条 + 灰字「另有 N 条不适用（渠道不符…）」')
  await hold(page, PACE.action)
  await row.getByTestId('audit-row-why').click()
  const drawer = page.getByTestId('audit-why-drawer')
  await drawer.waitFor({ state: 'visible', timeout: 20_000 })
  await hold(page, PACE.settle)

  const oneLiner = (await page.getByTestId('audit-explain-oneliner').innerText()).trim()
  const closestName = (await page.getByTestId('audit-explain-closest').innerText()).trim()
  const naNote = (await page.getByTestId('audit-explain-not-applicable').innerText()).trim()
  if (!oneLiner) fail('第 2 步：抽屉第一层没有「一句人话」')
  if (!closestName.includes('最接近')) fail(`第 2 步：第一层没有「最接近那条」（实得「${closestName}」）`)
  if (!/另有 \d+ 条不适用/.test(naNote)) fail(`第 2 步：灰字里没有「另有 N 条不适用」（实得「${naNote}」）`)
  log(`▶ 第 2 步：一句人话「${oneLiner}」；${closestName.split('\n')[0]}；灰字「${naNote.split('\n')[0]}」`)
  await shoot('2-抽屉第一层最接近那条')

  /* ---------- 第 3 步：第二层（不适用明细） ---------- */
  await caption(page, '第 3 步 · 展开第二层：哪些策略压根不适用、各自为什么（渠道不符 / 客户标签不符）')
  await page.getByTestId('audit-explain-layer2-toggle').click()
  await hold(page, PACE.read)
  const layer2Body = (await page.getByTestId('audit-explain-layer2-body').innerText()).trim()
  if (!layer2Body) fail('第 3 步：第二层展开后没有内容')
  const layer2Lines = layer2Body.split('\n').map((s) => s.trim()).filter(Boolean)
  log(`▶ 第 3 步：第二层 ${layer2Lines.length} 行，例如「${layer2Lines.slice(0, 2).join(' / ')}」`)
  await shoot('3-第二层不适用明细')

  /* ---------- 第 4 步：第三层（完整矩阵） ---------- */
  await caption(page, '第 4 步 · 展开第三层：完整矩阵 —— 每条策略的每条条件、期望值与实际值')
  await page.getByTestId('audit-explain-layer3-toggle').click()
  await hold(page, PACE.read)
  const matrixText = (await page.getByTestId('audit-explain-layer3-body').innerText()).trim()
  const matrixPolicies = (await page.getByTestId('audit-explain-layer3-body').locator('.why-matrix-policy, .why-policy-name').count()) || 0
  if (!matrixText.includes('抖音-标准单')) fail('第 4 步：第三层矩阵里没有「抖音-标准单」（这单最接近的那条）')
  log(`▶ 第 4 步：第三层矩阵 ${matrixText.length} 字，含「抖音-标准单」（策略块 ${matrixPolicies} 个）`)
  await shoot('4-第三层完整矩阵')

  /* ---------- 第 5 步：按标筛选 ---------- */
  await caption(page, '第 5 步 · 按标筛选「无策略命中」：行数当场减少，剩下的每一行都真挂这枚标')
  await page.getByTestId('audit-why-drawer').locator('.el-drawer__close-btn').click()
  // 把鼠标挪回表格中部的空白处：关抽屉后鼠标停在右上角会弹出「退出登录」浮层，
  // 那条浮层与这一步毫无关系，却会占着画面（实测第一版片子里就有）。
  await page.mouse.move(1000, 640)
  await hold(page, PACE.settle)
  await hold(page, PACE.settle)

  await page.getByTestId('audit-queue-tag-filter').click()
  await hold(page, PACE.action)
  await page.getByTestId('audit-tag-option-NO_POLICY_MATCH').click()
  await hold(page, PACE.read)
  await page.waitForTimeout(1200)   // 等表格重渲染完再数行（数完再截图）

  const facts = await queueFacts(page)
  const rowCountAfter = facts.length
  if (process.env.T5_DEBUG) {
    const bodies = await page.evaluate(() => [...document.querySelectorAll('.el-table__body')]
      .map((b) => ({ parent: (b.parentElement?.className || '').slice(0, 80), rows: b.querySelectorAll('tr').length })))
    log(`   [debug] bodies=${JSON.stringify(bodies)} drawerVisible=${await page.getByTestId('audit-why-drawer').isVisible()}`)
  }
  const allCarry = facts.every((f) => f.codes.includes('NO_POLICY_MATCH'))
  if (rowCountAfter === 0) fail('第 5 步：按标筛出 0 行 —— 这一步要的是「筛得出单」')
  if (rowCountAfter >= rowCountBefore) fail(`第 5 步：筛完还是 ${rowCountAfter} 行（筛前 ${rowCountBefore}），没有减少`)
  if (!allCarry) {
    const bad = facts.filter((f) => !f.codes.includes('NO_POLICY_MATCH')).map((f) => `${f.sn}[${f.codes.join(',')}]`)
    fail(`第 5 步：筛出来的行里有没挂该标的：${bad.join(' ')}`)
  }
  log(`▶ 第 5 步：筛前 ${rowCountBefore} 行 → 选「无策略命中」后 ${rowCountAfter} 行；`
    + `每行都真挂该标=${allCarry}（${facts.map((f) => f.sn).join(', ')}）`)
  const pagerTotal = (await page.getByTestId('audit-queue-pager').innerText()).replace(/\s+/g, ' ')
  log(`   分页器原话：${pagerTotal}`)
  await hold(page, PACE.read)
  await shoot('5-按标筛选筛出单')
  // 收尾：把字幕撤掉，让「共 4 条」的分页器完整露在画面里 —— 底边那条字幕正好压着它。
  // 这是本片唯一一次撤字幕，也是一句「这一屏就是结论」的停顿。
  if (MODE === 'video') {
    await page.evaluate(() => document.getElementById('t5-caption')?.remove())
    await page.waitForTimeout(3500)
  }

  /* ---------- 收尾 ---------- */
  const totalSeconds = MODE === 'video' ? null : undefined
  await context.close()
  await browser.close()

  if (MODE === 'video') {
    /* webm → mp4（H.264）。与 collect-demo.mjs 同一套做法：系统 ffmpeg，出 H.264 + yuv420p，
       `-movflags +faststart` 让浏览器不用下完就能播。 */
    const webms = readdirSync(videoTmp).filter((f) => f.endsWith('.webm'))
    if (webms.length !== 1) fail(`录屏目录里应有且只有一个 webm，实得 ${webms.length} 个：${webms.join(',')}`)
    const src = join(videoTmp, webms[0])
    mkdirSync(dirname(VIDEO_OUT), { recursive: true })
    execFileSync('ffmpeg', ['-y', '-i', src, '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '24',
      '-pix_fmt', 'yuv420p', '-movflags', '+faststart', '-an', VIDEO_OUT], { stdio: ['ignore', 'ignore', 'pipe'] })
    const probe = execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration,size',
      '-of', 'default=noprint_wrappers=1:nokey=1', VIDEO_OUT], { encoding: 'utf8' }).trim().split('\n')
    const [dur, size] = probe
    rmSync(videoTmp, { recursive: true, force: true })
    log(`▶ 视频：${VIDEO_OUT}`)
    log(`   时长 ${Number(dur).toFixed(1)} 秒（${(Number(dur) / 60).toFixed(2)} 分钟）｜大小 ${(Number(size) / 1048576).toFixed(2)} MiB`)
    if (Number(dur) > 180) fail(`录屏 ${Number(dur).toFixed(0)} 秒 > 180 秒（要求 ≤3 分钟）`)
  } else {
    log(`▶ 截图 ${shotsMade.length} 张 → ${SHOT_DIR}`)
  }
  log('✓ 五步全过。')
} catch (e) {
  await context.close().catch(() => {})
  await browser.close().catch(() => {})
  fail(String(e && e.stack ? e.stack.split('\n').slice(0, 4).join('\n') : e))
}
