#!/usr/bin/env node
/**
 * 环境 pre-check：容器镜像是不是比源码旧（防「改完没发布」）。
 *
 * 背景：容器镜像是构建时的快照，源码改了不会自动生效，今天就这样踩了一次——
 * admin-web 源码 11-11 14:55 改完，而 qiqi-admin-web 镜像还是 11-10 19:45 的，
 * 容器栈跑的是旧代码。这种落差不会报错，只在用例里表现为「莫名其妙的失败」。
 *
 * 判定：源码目录最新 mtime > 镜像 Created 时间 → 该服务「过期」。
 *
 * 两种模式：
 *   默认（npm pretest 用）    只警告，exit 0 —— dev 栈测试不需要容器最新
 *   --strict（npm predemo 用）过期就 exit 1 —— demo 打容器栈，旧代码必错
 *
 * 已知局限（够用即止）：
 * - 用文件 mtime 判定会因 `git checkout` / 解压 / rsync 而失真（mtime 可能比镜像更早），
 *   从而漏报；反之 touch 一下就会误报。更可靠的做法是构建时给镜像打 git commit label
 *   （如 `--label git.commit=$(git rev-parse HEAD)`），运行时比对 HEAD，未提交改动再补一条
 *   `git status --porcelain` 判断。那是下一步，这里只做零改动的 mtime 版本。
 * - docker 不可用 / 镜像不存在 / 命令超时 → 打印「无法检测，跳过」并 exit 0，
 *   检查本身不能把测试搞挂。
 */
import { execFileSync } from 'node:child_process'
import { existsSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const E2E_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const REPO_DIR = resolve(E2E_DIR, '..')
const STRICT = process.argv.includes('--strict')

/** 服务 → { 源码目录, 镜像名, 重建命令 } */
const SERVICES = [
  { name: 'admin-web', src: join(REPO_DIR, 'admin-web/src'), image: 'qiqi-admin-web:prod', fix: 'docker compose up -d --build admin-web' },
  { name: 'portal-web', src: join(REPO_DIR, 'portal-web/src'), image: 'qiqi-portal-web:prod', fix: 'docker compose up -d --build portal-web' },
  { name: 'server', src: join(REPO_DIR, 'qiqi-mall-server/src'), image: 'qiqi-mall-server:prod', fix: 'docker compose up -d --build server-1 server-2' }
]

const IGNORE_DIRS = new Set(['node_modules', 'target', 'dist', '.git', '.vite', 'test-results', 'report'])
const SKIP_EXT = /\.(log|tmp|swp|zip)$/i

function run(cmd, args, timeout = 10_000) {
  return execFileSync(cmd, args, { encoding: 'utf8', timeout, stdio: ['ignore', 'pipe', 'pipe'] }).trim()
}

/** 递归找目录下最新的 mtime（毫秒） */
function newestMtime(dir) {
  let newest = 0
  let newestFile = ''
  const stack = [dir]
  while (stack.length) {
    const cur = stack.pop()
    let entries
    try {
      entries = readdirSync(cur, { withFileTypes: true })
    } catch {
      continue
    }
    for (const e of entries) {
      if (e.isDirectory()) {
        if (!IGNORE_DIRS.has(e.name)) stack.push(join(cur, e.name))
        continue
      }
      if (SKIP_EXT.test(e.name)) continue
      const full = join(cur, e.name)
      let st
      try {
        st = statSync(full)
      } catch {
        continue
      }
      if (st.mtimeMs > newest) {
        newest = st.mtimeMs
        newestFile = full
      }
    }
  }
  return { mtime: newest, file: newestFile }
}

const pad = (n) => String(n).padStart(2, '0')
const fmt = (ms) => {
  const d = new Date(ms)
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

// docker 不可用 → 跳过（不能让检查把测试搞挂）
try {
  run('docker', ['version', '--format', '{{.Server.Version}}'])
} catch (e) {
  console.log(`ℹ️  pre-check：docker 不可用（${String(e.message).split('\n')[0]}），无法检测镜像新旧，跳过`)
  process.exit(0)
}

const stale = []
const unknown = []

for (const svc of SERVICES) {
  if (!existsSync(svc.src)) {
    unknown.push(`${svc.name}（源码目录不存在：${svc.src}）`)
    continue
  }
  let imageCreated
  try {
    imageCreated = run('docker', ['image', 'inspect', svc.image, '--format', '{{.Created}}'])
  } catch {
    unknown.push(`${svc.name}（镜像 ${svc.image} 不存在或 inspect 失败）`)
    continue
  }
  const createdMs = Date.parse(imageCreated)
  if (!Number.isFinite(createdMs)) {
    unknown.push(`${svc.name}（镜像 Created 时间无法解析：${imageCreated}）`)
    continue
  }
  const { mtime, file } = newestMtime(svc.src)
  if (!mtime) {
    unknown.push(`${svc.name}（源码目录里没有文件）`)
    continue
  }
  if (mtime > createdMs) {
    stale.push({ ...svc, srcMs: mtime, imgMs: createdMs, file })
  }
}

for (const u of unknown) console.log(`ℹ️  pre-check：无法检测 ${u}，跳过`)

if (!stale.length) {
  console.log('✅ pre-check：容器镜像不比源码旧（或无法检测的已跳过）')
  process.exit(0)
}

console.log('')
for (const s of stale) {
  console.log(`⚠️ ${s.name} 源码(${fmt(s.srcMs)}) 比镜像(${fmt(s.imgMs)}) 新 —— 容器栈跑的是旧代码`)
  console.log(`   最新改动：${s.file.replace(REPO_DIR + '/', '')}`)
  console.log(`   修复：${s.fix}`)
}

if (STRICT) {
  console.log('\n❌ demo 档要求容器栈是最新代码，已阻断。重建后重跑 npm run demo。')
  process.exit(1)
}

console.log('\n（验收档只警告不阻断：dev 栈测试用的是源码热更新，不需要重建镜像）')
process.exit(0)
