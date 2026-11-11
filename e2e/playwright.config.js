import { defineConfig, devices } from '@playwright/test'

/**
 * 五个业务场景的 E2E 配置。
 *
 * 两档运行方式，同一份 spec 代码共用，断言一行不改：
 *
 *  1) 验收档（默认，`npm test`）：跑本地开发栈，快，不录视频、不降速
 *       PORTAL_BASE  http://127.0.0.1:5173
 *       ADMIN_BASE   http://127.0.0.1:5175
 *       API_BASE     http://127.0.0.1:8081
 *
 *  2) demo 档（`npm run demo`，即 DEMO=1）：跑容器栈 = 测试环境，1280x720 全量录制 + slowMo 400
 *       PORTAL_BASE  http://127.0.0.1:5174   (portal-web 容器 nginx)
 *       ADMIN_BASE   http://127.0.0.1:5176   (admin-web 容器 nginx)
 *       API_BASE     http://127.0.0.1:18080  (gateway → server-1/server-2 + Redis)
 *     视频落在 report/videos/raw/，用 `npm run demo:collect` 统一改名 + 合并到 report/videos/
 *
 * 目录结构：
 *   tests/scenarios/  五个业务场景用例
 *   tests/support/    ui.js（真实 UI 操作）/ assert.js（只读断言）/ data.js（页面无入口的数据准备）/ env.js（地址与工具）
 */
const CHROME = '/root/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome'

/**
 * 并行 shard 说明（配合 scripts/e2e-run.sh）：
 *   每个 shard 由**一个独立的 playwright 进程**承担，进程级用环境变量切换目标地址
 *   （PORTAL_BASE/ADMIN_BASE/API_BASE，见 tests/support/env.js），并用 `--shard=i/N` 切文件。
 *
 *   为什么不用 projects 里各自的 use.baseURL：spec 里的接口调用、跳转都走 env.js 导出的
 *   **绝对地址**，而 env.js 在模块加载时读 process.env —— 同一个进程里的两个 project 共享
 *   同一份 env，per-project 的 use.baseURL 只能影响相对路径的 page.goto，改不了 API 地址。
 *   不改 spec/support（其他 agent 正在写）的前提下，进程级分片是唯一干净的做法。
 */
const E2E_WORKERS = Number(process.env.E2E_WORKERS || 1)      // 每个 shard 进程内的 worker 数
const E2E_SHARD_NAME = process.env.E2E_SHARD_NAME || 'a'      // 只用于报告/产物目录区分
const E2E_HTML_DIR = process.env.E2E_HTML_DIR || 'report/html'
const E2E_OUT_DIR = process.env.E2E_OUT_DIR || './test-results'

/** demo 档：全量录制 + 降速，方便人看；验收档保持快速干净 */
const DEMO = !!process.env.DEMO
const DEMO_VIEWPORT = { width: 1280, height: 720 }

export default defineConfig({
  testDir: './tests/scenarios',
  // demo 档每个动作多等 400ms，单用例墙钟时间会明显变长，给足超时避免录到一半被判超时
  timeout: DEMO ? 300_000 : 120_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  // 默认 1（保持 `npm test` 打 dev 栈时的老行为；多个 agent 共用 8081/dev 库，串行才不打架）。
  // 走 e2e 独立库 + 多 shard 时由 scripts/e2e-run.sh 传 E2E_WORKERS。
  workers: E2E_WORKERS,
  retries: 0,
  // HTML 报告单独放 report/html：它每次运行前会清空自己的输出目录，不能和 demo 视频共用 report/
  // 每个 shard 一个独立 HTML 目录：两个进程同时写同一个 outputFolder 会互相清空/写坏
  reporter: [['list'], ['html', { open: 'never', outputFolder: E2E_HTML_DIR }]],
  // demo 档把视频和 trace 单独放到 report/videos/raw，便于统一收集（report/ 已被 .gitignore 覆盖）
  outputDir: DEMO ? './report/videos/raw' : E2E_OUT_DIR,
  use: {
    baseURL: process.env.PORTAL_BASE || 'http://127.0.0.1:5173',
    screenshot: DEMO ? 'on' : 'only-on-failure',
    // 注意：Playwright 1.65 没有 use.videoSize，尺寸要写在 video 对象里，否则默认 800x800 缩放
    video: DEMO ? { mode: 'on', size: DEMO_VIEWPORT } : 'retain-on-failure',
    trace: 'retain-on-failure',
    viewport: DEMO ? DEMO_VIEWPORT : { width: 1440, height: 950 },
    launchOptions: {
      executablePath: CHROME,
      args: ['--no-sandbox', '--disable-dev-shm-usage'],
      slowMo: DEMO ? 400 : 0
    }
  },
  // 只用来给报告标注当前 shard；真正的地址分流在进程级（见文件头说明）
  projects: [{ name: `chromium-${E2E_SHARD_NAME}`, use: { ...devices['Desktop Chrome'] } }]
})
