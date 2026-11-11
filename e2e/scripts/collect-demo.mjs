#!/usr/bin/env node
/**
 * demo 视频整理：把 Playwright 录到的原始视频改名成人能看的场景名，并合并成一条全流程片。
 *
 * 输入（`npm run demo` 产出）：report/videos/raw/<用例标题-sanitized>/video.webm
 * 输出（report/videos/，全部被 .gitignore 覆盖）：
 *   01-商品浏览.webm … 05-拆单履约.webm  每个场景一条（场景3 的两个用例先合成一条）
 *   demo-全流程.webm                     五段按场景顺序合并
 *   demo-全流程.mp4                      同上，转 H.264，方便在不装 webm 解码器的播放器里看
 *
 * ffmpeg 选择：优先系统 ffmpeg/ffprobe（能出 mp4）；只有 Playwright 自带那份时退化为纯 webm
 * （自带 build 只编了 webm muxer + libvpx-vp9）。
 *
 * 用法：npm run demo && npm run demo:collect
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const E2E_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const RAW_DIR = join(E2E_DIR, 'report/videos/raw')
const OUT_DIR = join(E2E_DIR, 'report/videos')

/** 场景号 → 输出文件名（顺序即合并顺序） */
const SCENARIOS = {
  1: '01-商品浏览',
  2: '02-购买下单',
  3: '03-订单履约',
  4: '04-售后退货',
  5: '05-拆单履约'
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

function which(bin) {
  try {
    execFileSync('sh', ['-c', `command -v ${bin}`], { stdio: 'pipe' })
    return bin
  } catch {
    return null
  }
}

const FFMPEG = which('ffmpeg') || which('/root/.cache/ms-playwright/ffmpeg-1013/ffmpeg-linux')
const FFPROBE = which('ffprobe')
if (!FFMPEG) {
  console.error('✗ 找不到 ffmpeg（系统与 Playwright 自带都没有）')
  process.exit(1)
}
const CAN_MP4 = (() => {
  try {
    const out = execFileSync(FFMPEG, ['-hide_banner', '-muxers'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    return /\bmp4\b/.test(out)
  } catch {
    return false
  }
})()

const fmtSize = (bytes) => `${(bytes / 1024 / 1024).toFixed(1)} MB`

/** 时长：优先 ffprobe，没有就用 ffmpeg -i 的 Duration 行 */
function duration(file) {
  if (FFPROBE) {
    try {
      const out = execFileSync(FFPROBE, [
        '-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', file
      ], { encoding: 'utf8' })
      return Number(out.trim())
    } catch { /* 落到下面 */ }
  }
  try {
    const out = execFileSync(FFMPEG, ['-i', file], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
    const m = out.match(/Duration: (\d+):(\d+):(\d+\.\d+)/)
    return m ? Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]) : NaN
  } catch (e) {
    const m = String(e.stderr || '').match(/Duration: (\d+):(\d+):(\d+\.\d+)/)
    return m ? Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]) : NaN
  }
}

const mmss = (s) => (Number.isFinite(s) ? `${Math.floor(s / 60)}分${(s % 60).toFixed(1)}秒` : '未知')

function concat(inputs, outFile) {
  const listFile = join(OUT_DIR, '.concat-list.txt')
  writeFileSync(listFile, inputs.map((f) => `file '${f}'`).join('\n') + '\n')
  execFileSync(FFMPEG, ['-y', '-hide_banner', '-loglevel', 'error', '-f', 'concat', '-safe', '0', '-i', listFile, '-c', 'copy', outFile], { stdio: 'inherit' })
  rmSync(listFile, { force: true })
}

// ---------------------------------------------------------------------------
// 1. 收集原始视频并按场景分组
// ---------------------------------------------------------------------------

if (!existsSync(RAW_DIR)) {
  console.error(`✗ 没找到原始视频目录 ${RAW_DIR}\n  先跑 npm run demo（DEMO=1）`)
  process.exit(1)
}

const raws = []
for (const entry of readdirSync(RAW_DIR, { withFileTypes: true })) {
  if (!entry.isDirectory()) continue
  const video = join(RAW_DIR, entry.name, 'video.webm')
  if (!existsSync(video)) continue
  const n = entry.name.match(/场景(\d)/)
  if (!n) {
    console.warn(`! 跳过无法识别场景号的目录：${entry.name}`)
    continue
  }
  raws.push({ scenario: Number(n[1]), dirName: entry.name, video, order: entry.name.includes('确认收货') ? 9 : 0 })
}

if (!raws.length) {
  console.error('✗ raw 目录里没有 video.webm，demo 档没录上？')
  process.exit(1)
}

// 同一场景内按「确认收货类放最后」排序，其余按目录名
raws.sort((a, b) => a.scenario - b.scenario || a.order - b.order || a.dirName.localeCompare(b.dirName))

mkdirSync(OUT_DIR, { recursive: true })

// ---------------------------------------------------------------------------
// 2. 每个场景一条（多段先合并）
// ---------------------------------------------------------------------------

const results = []
for (const num of Object.keys(SCENARIOS).map(Number)) {
  const segs = raws.filter((r) => r.scenario === num)
  if (!segs.length) {
    console.warn(`! 场景${num} 没有视频，跳过`)
    continue
  }
  const out = join(OUT_DIR, `${SCENARIOS[num]}.webm`)
  if (segs.length === 1) {
    rmSync(out, { force: true })
    renameSync(segs[0].video, out)
  } else {
    concat(segs.map((s) => s.video), out)
  }
  results.push({ label: SCENARIOS[num], file: out, segments: segs.length, seconds: duration(out), bytes: statSync(out).size })
}

// ---------------------------------------------------------------------------
// 3. 按场景顺序合并成一条全流程片
// ---------------------------------------------------------------------------

const ordered = results.map((r) => r.file)
if (ordered.length > 1) {
  const merged = join(OUT_DIR, 'demo-全流程.webm')
  concat(ordered, merged)
  const seconds = duration(merged)
  const bytes = statSync(merged).size
  results.push({ label: 'demo-全流程', file: merged, segments: ordered.length, seconds, bytes, merged: true })

  if (CAN_MP4) {
    const mp4 = join(OUT_DIR, 'demo-全流程.mp4')
    execFileSync(FFMPEG, ['-y', '-hide_banner', '-loglevel', 'error', '-i', merged,
      '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '28', '-preset', 'veryfast', '-movflags', '+faststart', mp4], { stdio: 'inherit' })
    results.push({ label: 'demo-全流程.mp4', file: mp4, segments: ordered.length, seconds: duration(mp4), bytes: statSync(mp4).size, merged: true })
  }
}

// ---------------------------------------------------------------------------
// 4. 报告
// ---------------------------------------------------------------------------

const sum = results.filter((r) => !r.merged).reduce((s, r) => s + (r.seconds || 0), 0)
console.log('\n=== demo 视频 ===')
for (const r of results) {
  console.log(`${r.label.padEnd(16)} ${mmss(r.seconds).padStart(12)}  ${fmtSize(r.bytes).padStart(8)}  ${r.file.replace(E2E_DIR + '/', '')}${r.segments > 1 ? `（${r.segments} 段合并）` : ''}`)
}
const mergedWebm = results.find((r) => r.label === 'demo-全流程')
if (mergedWebm) {
  const delta = (mergedWebm.seconds || 0) - sum
  console.log(`\n五段合计 ${mmss(sum)}；合并片 ${mmss(mergedWebm.seconds)}；差值 ${delta.toFixed(2)}s`)
}
