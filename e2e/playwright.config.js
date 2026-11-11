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

/** demo 档：全量录制 + 降速，方便人看；验收档保持快速干净 */
const DEMO = !!process.env.DEMO
const DEMO_VIEWPORT = { width: 1280, height: 720 }

export default defineConfig({
  testDir: './tests/scenarios',
  // demo 档每个动作多等 400ms，单用例墙钟时间会明显变长，给足超时避免录到一半被判超时
  timeout: DEMO ? 300_000 : 120_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  // HTML 报告单独放 report/html：它每次运行前会清空自己的输出目录，不能和 demo 视频共用 report/
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'report/html' }]],
  // demo 档把视频和 trace 单独放到 report/videos/raw，便于统一收集（report/ 已被 .gitignore 覆盖）
  outputDir: DEMO ? './report/videos/raw' : './test-results',
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
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }]
})
