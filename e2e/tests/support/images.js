/**
 * 图片链路相关的小工具：临时图片生成、真实上传、渲染校验。
 *
 * 目的：让 e2e 能断言「图片真的加载出来了」，而不是只断言 DOM 里有个 <img>。
 * 前端 el-image 有 error 兜底（灰图标 / "无法加载"），所以只查元素存在会被兜底骗过去。
 */
import { expect } from '@playwright/test'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { API, ADMIN } from './env.js'
import { imageAt, imageFor, realImages } from './fixtures.js'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

/** 一张 120x120 的纯色 PNG（合法文件，浏览器能解码，naturalWidth 不为 0） */
const TINY_PNG_BASE64 = 'iVBORw0KGgoAAAANSUhEUgAAAHgAAAB4CAIAAAC2BqGFAAAACXBIWXMAAAABAAAAAQBPJcTWAAAA60lEQVR4nO3QAQkAIADAMOuZznjWsYXCHTzA2Zhr60Lj+cEngQbdCjToVqBBtwINuhVo0K1Ag24FGnQr0KBbgQbdCjToVqBBtwINuhVo0K1Ag24FGnQr0KBbgQbdCjToVqBBtwINuhVo0K1Ag24FGnQr0KBbgQbdCjToVqBBtwINuhVo0K1Ag24FGnQr0KBbgQbdCjToVqBBtwINuhVo0K1Ag24FGnQr0KBbgQbdCjToVqBBtwINuhVo0K1Ag24FGnQr0KBbgQbdCjToVqBBtwINuhVo0K1Ag24FGnQr0KBbgQbdCjToVqBBtzqWwblDxtk3RQAAAABJRU5ErkJggg=='

let tempDir = null

/** demo 档：界面/视频里要好看，图片用真实种子图而不是纯色块 */
export const IS_DEMO = !!process.env.DEMO

/**
 * `demoImageFile()` 的轮转游标：不给 index 时按调用顺序自动取下一张，
 * 这样「同一批里建的第 1/2/3 个商品」拿到的种子图互不相同（审计 B 项：
 * 以前不管建几个商品都返回同一张 1-cover，买家首页就是一整屏同款主图）。
 */
let demoImageSeq = 0

/** 重置轮转游标（需要「从头开始轮」的用例调用；临时目录清理时也会重置） */
export function resetDemoImageRotation() {
  demoImageSeq = 0
}

/**
 * 取一张"真实图片"给上传用：
 * - demo 档：从 MinIO（经前端 nginx 反代）下载种子商品图，写成临时文件上传 → 视频里是真实照片
 *   种子封面池（fixtures.realImages）里只有 4 张互不相同的图，按 index 轮转取；
 *   不传 index 就按调用顺序轮转（同一批多商品 → 图不同）。
 * - 默认档：仍用纯色块小图（快、可重复、不依赖外部资源）
 *
 * @param index 取第几张种子封面（0 起，越界自动绕回）；省略则用内部自增游标
 */
export async function demoImageFile(index) {
  if (!IS_DEMO) return writeTempPng()
  const i = index === undefined ? demoImageSeq++ : index
  const rel = imageFor(i)
  const res = await fetch(`${ADMIN}${rel}`)
  if (!res.ok) throw new Error(`下载种子图失败 ${rel} ${res.status}`)
  const buf = Buffer.from(await res.arrayBuffer())
  if (!tempDir) tempDir = mkdtempSync(join(tmpdir(), 'qiqi-e2e-img-'))
  const file = join(tempDir, `seed-${String(imageAt(i, realImages.length)).padStart(2, '0')}.webp`)
  writeFileSync(file, buf)
  return file
}

/** demo 档用自然中文名（客户名/商品名/分类名），默认档保留 E2E 唯一前缀便于定位 */
export function demoName(demoValue, fallbackValue) {
  return IS_DEMO ? demoValue : fallbackValue
}

/** demo 档用自然用户名（像真实会员号），默认档返回 null 由调用方用 e2e_ 前缀 */
export function demoUsername(base) {
  return IS_DEMO ? `${base}${String(Date.now()).slice(-4)}` : null
}

/** demo 档用自然中文商品名（不含 E2E/时间戳），默认档保持唯一前缀便于定位 */
export function naturalName(index, fallback) {
  const pool = ['手冲咖啡滤杯', '亚麻抱枕套', '陶瓷马克杯', '藤编收纳筐', '胡桃木餐具套装', '亚克力相框']
  return IS_DEMO ? pool[index % pool.length] : fallback
}

/** 生成临时 png，返回路径；同一进程内只建一个临时目录，最后用 cleanupTempImages() 删掉 */
export function writeTempPng(name = 'e2e-image.png') {
  if (!tempDir) tempDir = mkdtempSync(join(tmpdir(), 'qiqi-e2e-img-'))
  const file = join(tempDir, name)
  writeFileSync(file, Buffer.from(TINY_PNG_BASE64, 'base64'))
  return file
}

/** 删掉临时图片目录（用例结束调用，避免留垃圾）；顺手重置种子图轮转游标 */
export function cleanupTempImages() {
  if (tempDir) {
    rmSync(tempDir, { recursive: true, force: true })
    tempDir = null
  }
  resetDemoImageRotation()
}

/** 一张 5.5MB 的 PNG（越界用）：超过后端 5MB 上限、小于前端 nginx 的 6m，确保能打到后端的 413 */
export function writeOversizePng(name = 'e2e-oversize.png') {
  if (!tempDir) tempDir = mkdtempSync(join(tmpdir(), 'qiqi-e2e-img-'))
  const file = join(tempDir, name)
  const buf = Buffer.alloc(Math.round(5.5 * 1024 * 1024), 0x41)
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(buf, 0)
  writeFileSync(file, buf)
  return file
}

/**
 * 通过后端真实上传接口传一张图，返回相对 URL（形如 /qiqi-mall/products/2026/11/uuid.png）。
 * 仅用于「场景1 造商品」这类数据准备，断言不依赖它推进业务状态。
 */
export async function uploadImageApi(request, token, filePath, contentType = 'image/png') {
  const { readFileSync } = await import('node:fs')
  // 注意：request fixture 的 baseURL 是 C 端（5173/5174），上传接口在后台侧，必须用绝对地址
  const res = await request.post(`${API}/admin/upload/image`, {
    headers: { Authorization: `Bearer ${token}` },
    multipart: {
      file: { name: 'e2e.png', mimeType: contentType, buffer: readFileSync(filePath) }
    }
  })
  expect(res.status(), '上传接口应返回 200').toBe(200)
  const body = await res.json()
  expect(body.code, `上传应成功：${body.message}`).toBe(0)
  return body.data.url
}

/**
 * 断言某个 <img> 真的把图解码出来了（naturalWidth > 0 且 complete）。
 * @param locator 指向 <img> 的 locator
 * @param label 失败时的提示
 */
export async function expectImageLoaded(locator, label = '图片') {
  await expect(locator, `${label} 应出现在页面上`).toBeVisible()
  await expect
    .poll(async () => locator.evaluate((el) => el.complete === true && el.naturalWidth > 0), {
      message: `${label} 应真正加载成功（complete && naturalWidth > 0），而不是走 error 兜底`,
      timeout: 10_000
    })
    .toBe(true)
}

/** 断言一组 <img> 全部真的加载出来 */
export async function expectAllImagesLoaded(locator, label = '图片') {
  const count = await locator.count()
  expect(count, `${label} 至少应有 1 张`).toBeGreaterThan(0)
  const broken = await locator.evaluateAll((els) =>
    els.map((el, i) => ({ i, ok: el.complete === true && el.naturalWidth > 0, src: el.currentSrc || el.src }))
      .filter((x) => !x.ok)
  )
  expect(broken, `${label} 有 ${broken.length}/${count} 张没加载出来：${JSON.stringify(broken)}`).toEqual([])
}
