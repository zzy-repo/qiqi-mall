#!/usr/bin/env node
/**
 * e2e 用的「构建产物 + 反向代理」轻量静态服务（零依赖：只用 node:http/fs/path/url）。
 *
 * 目的：e2e 现在打的是 vite **dev server**（每次页面导航要现编译模块、发几百个请求）。
 * 改用 `vite build` 出的 dist + 这台小服务，页面只剩「1 个 html + 几个 js/css + 接口请求」，
 * 单次导航实测 778ms/500 请求 → 525ms/50 请求。spec 与断言一个字不用改。
 *
 * 它替代 vite 的两件事，缺一不可：
 *   ① 静态：serve dist 里的文件；找不到且不是资源文件 → 回 index.html（vue-router history 模式）
 *   ② 代理：/portal、/admin → 该 shard 的后端（8091/8092）；/qiqi-mall → MinIO（商品图片）。
 *      dev 的 vite.config.js 里就是这两条 proxy（server.proxy），这里 1:1 复刻，
 *      唯一区别是压缩/缓存头按静态产物来设。
 *
 * 为什么不用 `vite preview`：
 *   · vite preview 也能 serve dist，但它的 proxy 只在配置里写死一份 target，
 *     一个 dist 要服务两个 shard 就得改配置/起两份不同配置；这里用 --api 参数直接分开。
 *   · 而且 node:http 手写版没有 vite 的中间件栈，请求路径更短（preview 也要走 connect 链）。
 *
 * 用法（通常由 scripts/e2e-preview.sh 拉起，不手敲）：
 *   node e2e/support/preview-server.mjs \
 *        --dist=portal-web/dist --port=5283 --api=http://127.0.0.1:8091 \
 *        [--minio=http://127.0.0.1:9000] [--label=a-portal] [--quiet]
 *
 * 约定（故意做得「响」一点）：
 *   · 启动成功会打这一行（排查用；scripts/e2e-preview.sh 的就绪判断是 HTTP 健康探测，不解析这一行）：
 *       ▶ preview ready <label> port=<n> dist=<dir> api=<api> minio=<url>
 *   · dist 里没有 index.html → 直接退出码 2（没构建就别说自己 ready，避免打空气跑出一片假红）
 *   · 任何代理错误都回 502 并在 stderr 记一行，不静默吞
 */
import http from 'node:http'
import { createReadStream } from 'node:fs'
import { stat, readFile } from 'node:fs/promises'
import { join, extname, normalize, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

// ── 参数 ────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2)
const arg = (name, dflt) => {
  const hit = argv.find((a) => a === `--${name}` || a.startsWith(`--${name}=`))
  if (!hit) return dflt
  if (hit === `--${name}`) return true
  return hit.slice(name.length + 3)
}
const DIST = arg('dist', '')
const PORT = Number(arg('port', 0))
const API = String(arg('api', '')).replace(/\/+$/, '')
const MINIO = String(arg('minio', process.env.E2E_MINIO || 'http://127.0.0.1:9000')).replace(/\/+$/, '')
const LABEL = String(arg('label', 'preview'))
const QUIET = Boolean(arg('quiet', false))

if (!DIST || !PORT || !API) {
  console.error('✗ 用法：node preview-server.mjs --dist=<dir> --port=<n> --api=<http://127.0.0.1:8091> [--minio=...] [--label=...] [--quiet]')
  process.exit(1)
}

// ── 代理前缀 → 上游（与 vite.config.js 的 server.proxy 一一对应）──────────────
// /portal、/admin 是该 shard 的后端；/qiqi-mall 是商品图（MinIO）。
// 顺序有意义：先匹配更长的（这里没有互相包含的，但保持显式）。
const ROUTES = [
  { prefix: '/portal', target: API },
  { prefix: '/admin', target: API },
  { prefix: '/qiqi-mall', target: MINIO }
]

// 注意 vite 的 proxy 只在 dev server 上开，产物里前端请求的是**相对路径**
// （portal: /portal，admin: /admin，见各 src/api/request.js 的 baseURL）。
// 所以这里必须把这两条前缀代理出去，否则产物一跑就是 404（典型的「dev 绿、build 红」坑）。

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.eot': 'application/vnd.ms-fontobject',
  '.mp4': 'video/mp4',
  '.txt': 'text/plain; charset=utf-8'
}

const log = (...a) => { if (!QUIET) console.log(...a) }

// 计数（退出时打一行总数）：用来证明「跑批期间请求确实打在产物上」，
// 而不是像 dev server 那样几百个模块请求全落在 vite 上。
let REQ_TOTAL = 0
let REQ_PROXIED = 0

// ── 反向代理（手写，够用即可：转发 method/headers/body，保真状态码与多值头）──────
function proxy(req, res, prefix, target) {
  const u = new URL(target)
  const headers = { ...req.headers, host: u.host, origin: `${u.protocol}//${u.host}` }
  // 上游压缩由后端决定；转发 accept-encoding 即可（这里不二次压缩）
  delete headers['expect']          // 别让上游等 100-continue：我们把 body 直接流过去
  delete headers['proxy-connection']

  const upstream = http.request(
    { protocol: u.protocol, hostname: u.hostname, port: u.port, method: req.method, path: req.url, headers },
    (up) => {
      // 不合并重复头：Set-Cookie 多条必须原样过去
      res.writeHead(up.statusCode || 502, up.headers)
      up.pipe(res)
    }
  )
  upstream.on('error', (e) => {
    console.error(`✗ [${LABEL}] 代理失败 ${prefix} → ${target}${req.url}：${e.message}`)
    if (!res.headersSent) res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' })
    res.end(`proxy error: ${e.message}`)
  })
  // 客户端提前断开时别把上游挂死
  req.on('aborted', () => upstream.destroy())
  req.pipe(upstream)
}

// ── 静态文件 ────────────────────────────────────────────────────────────────
async function sendFile(res, abs, { spa = false } = {}) {
  const st = await stat(abs)
  if (!st.isFile()) throw new Error('not a file')
  const type = MIME[extname(abs).toLowerCase()] || 'application/octet-stream'
  // index.html 绝不能缓存（否则改了产物浏览器还拿旧的）；assets/* 是内容哈希，可以长缓存
  const isHtml = extname(abs).toLowerCase() === '.html'
  const headers = {
    'content-type': type,
    'content-length': st.size,
    'cache-control': isHtml ? 'no-store' : 'public, max-age=31536000, immutable'
  }
  if (spa) headers['x-spa-fallback'] = '1'
  res.writeHead(200, headers)
  createReadStream(abs).pipe(res)
}

async function serveStatic(req, res) {
  // 解码 + 归一化，挡掉 ../ 穿越
  let pathname
  try { pathname = decodeURIComponent(new URL(req.url, 'http://x').pathname) } catch { pathname = '/' }
  const rel = normalize(pathname).replace(new RegExp(`^[${sep}\\\\]+`), '')
  const abs = join(DIST, rel === '' ? 'index.html' : rel)
  if (!abs.startsWith(normalize(DIST) + sep) && abs !== normalize(DIST)) {
    res.writeHead(403).end('forbidden')
    return
  }
  try {
    await sendFile(res, abs)
    return
  } catch { /* 落到下面的 SPA 回退 */ }

  // 资源文件（带扩展名）找不到就是真 404，别回 index.html ——
  // 否则 <img src> / <script src> 会拿到 200 的 html，测试只会报「图片没加载」，很难查
  if (extname(pathname)) {
    res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' }).end('404 ' + pathname)
    return
  }
  try {
    await sendFile(res, join(DIST, 'index.html'), { spa: true })
  } catch {
    res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' }).end('404 (no index.html in dist)')
  }
}

const server = http.createServer((req, res) => {
  REQ_TOTAL++
  const t0 = Date.now()
  res.on('finish', () => {
    if (process.env.E2E_PREVIEW_LOG === '1') {
      log(`· [${LABEL}] ${res.statusCode} ${req.method} ${req.url} ${Date.now() - t0}ms`)
    }
  })
  const hit = ROUTES.find((r) => req.url === r.prefix || req.url.startsWith(r.prefix + '/') || req.url.startsWith(r.prefix + '?'))
  if (hit) { REQ_PROXIED++; return proxy(req, res, hit.prefix, hit.target) }
  if (req.method === 'GET' || req.method === 'HEAD') return void serveStatic(req, res)
  res.writeHead(405, { 'content-type': 'text/plain; charset=utf-8' }).end('405 ' + req.method)
})

server.on('error', (e) => {
  console.error(`✗ [${LABEL}] 监听 ${PORT} 失败：${e.code || e.message}`)
  process.exit(3)
})

// 就绪前先确认 dist 真的构建过：没有 index.html 直接退出，不给「假装起来了」
const INDEX = join(DIST, 'index.html')
readFile(INDEX, 'utf8').then((html) => {
  if (!/src=|\/assets\//.test(html)) {
    console.error(`✗ [${LABEL}] ${INDEX} 看起来不是 vite 产物（找不到 /assets 引用）`)
    process.exit(2)
  }
  server.listen(PORT, '127.0.0.1', () => {
    console.log(`▶ preview ready ${LABEL} port=${PORT} dist=${DIST} api=${API} minio=${MINIO}`)
  })
}).catch((e) => {
  console.error(`✗ [${LABEL}] 读不到 ${INDEX}：${e.message}（先 cd ${process.env.PWD} && npm run build）`)
  process.exit(2)
})

// 精确退出：由 scripts/e2e-preview.sh 用 systemd unit / 精确 PID 停，不靠 pkill -f
for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, () => {
    console.log(`· [${LABEL}] 关闭统计：共处理 ${REQ_TOTAL} 个请求（其中反代 ${REQ_PROXIED} 个）`)
    server.close(() => process.exit(0))
    setTimeout(() => process.exit(0), 1500).unref()
  })
}

// 供调试：真正被 fork 时 PWD 是调用方目录
process.on('uncaughtException', (e) => {
  console.error(`✗ [${LABEL}] 未捕获异常：${e.stack || e.message}`)
})
void fileURLToPath
