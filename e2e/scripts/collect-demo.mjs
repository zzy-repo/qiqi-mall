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
 * 一次录制出多段视频（场景拆 -1/-2/-3 的标准做法）：
 *   # 同一次录制目录里按关键词取子集 + 指定顺序，各出一段；--from 默认保留，可重复取用
 *   node scripts/collect-demo.mjs --from /tmp/pwout-addr \
 *     --out report/videos/01-买家侧/7-收货地址-1-全流程.mp4 \
 *     --only "全流程,删除默认地址" --order "全流程,删除默认地址"
 *   node scripts/collect-demo.mjs --from /tmp/pwout-addr \
 *     --out report/videos/01-买家侧/7-收货地址-2-边界.mp4 \
 *     --only "必填校验,超长文本" --order "必填校验,超长文本" --clean
 *
 * 行为：
 * - `--only` 取子集（目录名包含任一关键词），不给就是整个录制目录
 * - `--order` 决定拼接顺序；**关键词必须命中至少一个片段，否则直接失败**（见下）
 * - 只输出 .mp4（webm 只当中间态）；时长 > 90 秒会告警，提示按子操作拆 -1/-2
 * - 默认**保留** --from 目录（一次录制常要出多段）；`--clean` 才清掉（`--keep` 是旧别名，等同默认）
 *
 * 为什么 --order 未命中要报错（踩过的坑）：
 *   Playwright 会把过长的用例标题截断成 `<前缀>-<5位hash>-<尾部>`，
 *   于是「新增商品（主图+相册+描述）→ 编辑 → 落库…」在目录名里变成「…商品-fcca5-图-相册-描述）→…」，
 *   按关键词就匹配不上。以前只把没命中的排到最后，结果是**顺序静默错乱但退出码 0**（假成功）。
 *   现在一律 exit 1 并打印未命中的关键词与实际片段标题，逼调用方改用能命中的关键词或 --only。
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
const splitKws = (v) => (v || '').split(',').map((s) => s.trim()).filter(Boolean)

const FROM = resolve(E2E_DIR, argOf('--from') || 'report/videos/raw')
const OUT = resolve(E2E_DIR, argOf('--out') || '')
const ORDER = splitKws(argOf('--order'))
const ONLY = splitKws(argOf('--only'))
const CLEAN = argv.includes('--clean')
const MAX_SECONDS = 90

const die = (msg) => {
  console.error(`✗ ${msg}`)
  process.exit(1)
}

/** 打印片段标题清单（报错/排查用）：Playwright 目录名 = <spec路径>-<describe>-<用例标题>-<project> */
const listClips = (clips) => clips.map((c) => `    · ${c.dir}`).join('\n')

if (!argOf('--out')) {
  die('缺少 --out，例如 --out report/videos/01-买家侧/7-收货地址.mp4')
}
if (!existsSync(FROM)) {
  die(`没找到录制目录 ${FROM}，先跑 npm run demo`)
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
  die('找不到系统 ffmpeg（Playwright 自带那份只能出 webm，不能转 mp4），请先安装 ffmpeg')
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
const all = []
for (const entry of readdirSync(FROM, { withFileTypes: true })) {
  if (!entry.isDirectory()) continue
  const video = join(FROM, entry.name, 'video.webm')
  if (existsSync(video)) all.push({ dir: entry.name, video })
}
if (!all.length) {
  die(`${FROM} 里没有 video.webm，demo 档没录上？`)
}

// ---- --only：取子集（每个关键词都必须命中，避免"少录了一段还静默出片"）----
let picked = all
if (ONLY.length) {
  const missing = ONLY.filter((kw) => !all.some((c) => c.dir.includes(kw)))
  if (missing.length) {
    die(`--only 有 ${missing.length} 个关键词没命中任何片段：${missing.map((k) => `「${k}」`).join(' ')}\n  实际片段：\n${listClips(all)}`)
  }
  picked = all.filter((c) => ONLY.some((kw) => c.dir.includes(kw)))
}

// ---- --order：关键词必须命中，否则报错（历史上这里静默失效过）----
if (ORDER.length) {
  const unmatched = ORDER.filter((kw) => !picked.some((c) => c.dir.includes(kw)))
  if (unmatched.length) {
    die(
      `--order 有 ${unmatched.length} 个关键词没命中任何片段：${unmatched.map((k) => `「${k}」`).join(' ')}\n` +
      `  注意：Playwright 会把过长用例标题截断成「前缀-<5位hash>-尾部」，长标题请改用能出现的关键词，或先 --only 收窄。\n` +
      `  待排序片段：\n${listClips(picked)}`
    )
  }
}

/** 按 --order 的关键词给出排序权重（此时每个关键词都已确认命中过） */
const orderOf = (name) => {
  if (!ORDER.length) return 0
  const hit = ORDER.findIndex((kw) => name.includes(kw))
  return hit < 0 ? ORDER.length + 1 : hit
}
const clips = picked
  .map((c) => ({ ...c, order: orderOf(c.dir) }))
  .sort((a, b) => a.order - b.order || a.dir.localeCompare(b.dir))

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
if (picked.length !== all.length) {
  console.log(`  说明：本次只用了 ${FROM.replace(E2E_DIR + '/', '')} 里 ${all.length} 段中的 ${picked.length} 段（--only）`)
}

// ---- 清理中间态（默认保留：一次录制常要出多段）----
if (CLEAN) {
  rmSync(FROM, { recursive: true, force: true })
  console.log(`  已清理中间目录 ${FROM.replace(E2E_DIR + '/', '')}`)
} else {
  console.log(`  中间目录已保留：${FROM.replace(E2E_DIR + '/', '')}（还要出别的段就别删；收尾时加 --clean）`)
}
