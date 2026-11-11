#!/usr/bin/env node
/**
 * demo 视频整理：把一次 Playwright 录制（可能是同一场景的多个用例）合成**每个场景一个 mp4**。
 *
 * 不再产出「全流程合并片」——演示只需要按场景单独出片。
 *
 * 输入：`npm run demo`（DEMO=1）产出的一次录制目录，里面有若干个 <用例目录>/video.webm
 * 输出：`report/videos/<类别目录>/<场景>.mp4`（与 tests/scenarios/<类别目录>/<场景>.spec.js、docs 图目录三层同构）
 *
 * 用法：
 *   npm run demo -- --grep "7-收货地址"        # 先录（DEMO 档，slowMo 400）
 *   node scripts/collect-demo.mjs \
 *     --from report/videos/raw \
 *     --out report/videos/01-买家侧/7-收货地址.mp4 \
 *     --order "全流程,必填校验,超长文本"
 *
 * 行为：
 * - 按 --order 里的关键词顺序拼接用例（一个场景内多个用例 → 一段）；没给 --order 就按目录名排序
 * - 只输出 .mp4（webm 只当中间态）；时长 > 90 秒会告警，提示按子操作拆 -1/-2
 * - 默认跑完清理 --from 目录（--keep 可保留）
 *
 * ffmpeg：优先系统的（能出 H.264 mp4）；只有 Playwright 自带那份时直接报错（它只编了 webm muxer）。
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const E2E_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..')

// ---- 参数 ----
const argv = process.argv.slice(2)
const argOf = (name) => {
  const i = argv.indexOf(name)
  return i >= 0 ? argv[i + 1] : undefined
}
const FROM = resolve(E2E_DIR, argOf('--from') || 'report/videos/raw')
const OUT = resolve(E2E_DIR, argOf('--out') || '')
const ORDER = (argOf('--order') || '').split(',').map((s) => s.trim()).filter(Boolean)
const KEEP = argv.includes('--keep')
const MAX_SECONDS = 90

if (!argOf('--out')) {
  console.error('✗ 缺少 --out，例如 --out report/videos/01-买家侧/7-收货地址.mp4')
  process.exit(1)
}
if (!existsSync(FROM)) {
  console.error(`✗ 没找到录制目录 ${FROM}，先跑 npm run demo`)
  process.exit(1)
}

// ---- ffmpeg ----
const which = (bin) => {
  try {
    execFileSync('sh', ['-c', `command -v ${bin}`], { stdio: 'pipe' })
    return bin
  } catch {
    return null
  }
}
const FFMPEG = which('ffmpeg')
const FFPROBE = which('ffprobe')
if (!FFMPEG) {
  console.error('✗ 找不到系统 ffmpeg（Playwright 自带那份只能出 webm，不能转 mp4），请先安装 ffmpeg')
  process.exit(1)
}

const secondsOf = (file) => {
  if (FFPROBE) {
    const out = execFileSync(FFPROBE, ['-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', file], { encoding: 'utf8' })
    return Number(out.trim())
  }
  const out = execFileSync(FFMPEG, ['-i', file], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).toString()
  const m = out.match(/Duration: (\d+):(\d+):(\d+\.\d+)/)
  return m ? Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]) : NaN
}

// ---- 收集本次录制里的 video.webm ----
const clips = []
for (const entry of readdirSync(FROM, { withFileTypes: true })) {
  if (!entry.isDirectory()) continue
  const video = join(FROM, entry.name, 'video.webm')
  if (existsSync(video)) clips.push({ dir: entry.name, video, order: orderOf(entry.name) })
}
if (!clips.length) {
  console.error(`✗ ${FROM} 里没有 video.webm，demo 档没录上？`)
  process.exit(1)
}
clips.sort((a, b) => a.order - b.order || a.dir.localeCompare(b.dir))

/** 按 --order 的关键词给出排序权重（没匹配到的一律排后面，保持目录名顺序） */
function orderOf(name) {
  if (!ORDER.length) return 0
  const hit = ORDER.findIndex((kw) => name.includes(kw))
  return hit < 0 ? ORDER.length + 1 : hit
}

// ---- 拼 + 转 mp4 ----
mkdirSync(dirname(OUT), { recursive: true })
const listFile = join(FROM, '.concat-list.txt')
writeFileSync(listFile, clips.map((c) => `file '${c.video}'`).join('\n') + '\n')

if (clips.length === 1) {
  execFileSync(FFMPEG, ['-y', '-hide_banner', '-loglevel', 'error', '-i', clips[0].video,
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '26', '-preset', 'veryfast', '-movflags', '+faststart', OUT], { stdio: 'inherit' })
} else {
  const mergedWebm = join(FROM, '.merged.webm')
  execFileSync(FFMPEG, ['-y', '-hide_banner', '-loglevel', 'error', '-f', 'concat', '-safe', '0', '-i', listFile, '-c', 'copy', mergedWebm], { stdio: 'inherit' })
  execFileSync(FFMPEG, ['-y', '-hide_banner', '-loglevel', 'error', '-i', mergedWebm,
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '26', '-preset', 'veryfast', '-movflags', '+faststart', OUT], { stdio: 'inherit' })
  rmSync(mergedWebm, { force: true })
}
rmSync(listFile, { force: true })

// ---- 报告 ----
const secs = secondsOf(OUT)
const mb = (statSync(OUT).size / 1024 / 1024).toFixed(1)
console.log('\n=== demo 视频 ===')
for (const c of clips) console.log(`  来源：${c.dir}`)
console.log(`${OUT.replace(E2E_DIR + '/', '')}`)
console.log(`  时长 ${secs.toFixed(1)}s，体积 ${mb} MB，由 ${clips.length} 段合成`)
if (secs > MAX_SECONDS) {
  console.warn(`  ⚠️ 超过 ${MAX_SECONDS}s 上限，建议按子操作拆成 <场景>-1-xxx.mp4 / <场景>-2-xxx.mp4`)
} else {
  console.log(`  ✓ 在 ${MAX_SECONDS}s 上限内`)
}

// ---- 清理中间态 ----
if (!KEEP) {
  rmSync(FROM, { recursive: true, force: true })
  console.log(`  已清理中间目录 ${FROM.replace(E2E_DIR + '/', '')}`)
}
