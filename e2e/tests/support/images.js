/**
 * 图片链路相关的小工具：临时图片生成、真实上传、渲染校验。
 *
 * 目的：让 e2e 能断言「图片真的加载出来了」，而不是只断言 DOM 里有个 <img>。
 * 前端 el-image 有 error 兜底（灰图标 / "无法加载"），所以只查元素存在会被兜底骗过去。
 */
import { expect } from '@playwright/test'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { API } from './env.js'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

/** 一张 120x120 的纯色 PNG（合法文件，浏览器能解码，naturalWidth 不为 0） */
const TINY_PNG_BASE64 = 'iVBORw0KGgoAAAANSUhEUgAAAHgAAAB4CAIAAAC2BqGFAAAACXBIWXMAAAABAAAAAQBPJcTWAAAA60lEQVR4nO3QAQkAIADAMOuZznjWsYXCHTzA2Zhr60Lj+cEngQbdCjToVqBBtwINuhVo0K1Ag24FGnQr0KBbgQbdCjToVqBBtwINuhVo0K1Ag24FGnQr0KBbgQbdCjToVqBBtwINuhVo0K1Ag24FGnQr0KBbgQbdCjToVqBBtwINuhVo0K1Ag24FGnQr0KBbgQbdCjToVqBBtwINuhVo0K1Ag24FGnQr0KBbgQbdCjToVqBBtwINuhVo0K1Ag24FGnQr0KBbgQbdCjToVqBBtwINuhVo0K1Ag24FGnQr0KBbgQbdCjToVqBBtzqWwblDxtk3RQAAAABJRU5ErkJggg=='

let tempDir = null

/** 生成临时 png，返回路径；同一进程内只建一个临时目录，最后用 cleanupTempImages() 删掉 */
export function writeTempPng(name = 'e2e-image.png') {
  if (!tempDir) tempDir = mkdtempSync(join(tmpdir(), 'qiqi-e2e-img-'))
  const file = join(tempDir, name)
  writeFileSync(file, Buffer.from(TINY_PNG_BASE64, 'base64'))
  return file
}

/** 删掉临时图片目录（用例结束调用，避免留垃圾） */
export function cleanupTempImages() {
  if (tempDir) {
    rmSync(tempDir, { recursive: true, force: true })
    tempDir = null
  }
}

/** 一张 6MB 的 PNG（越界用）：头 8 字节是真 PNG 魔数，其余填充，后端按体积先拦 */
export function writeOversizePng(name = 'e2e-oversize.png') {
  if (!tempDir) tempDir = mkdtempSync(join(tmpdir(), 'qiqi-e2e-img-'))
  const file = join(tempDir, name)
  const buf = Buffer.alloc(6 * 1024 * 1024, 0x41)
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
