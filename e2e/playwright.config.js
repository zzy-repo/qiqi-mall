import { defineConfig, devices } from '@playwright/test'

/**
 * 五个业务场景的 E2E 配置。
 *
 * 目标环境用环境变量覆盖，默认跑本地开发栈（验收档，快）：
 *   PORTAL_BASE  C 端买家商城（默认 http://127.0.0.1:5173）
 *   ADMIN_BASE   B 端管理后台（默认 http://127.0.0.1:5175）
 *   API_BASE     后端（默认 http://127.0.0.1:8081），仅用于只读断言
 *
 * 目录结构：
 *   tests/scenarios/  五个业务场景用例
 *   tests/support/    ui.js（真实 UI 操作）/ assert.js（只读断言）/ data.js（页面无入口的数据准备）/ env.js（地址与工具）
 */
const CHROME = '/root/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome'

export default defineConfig({
  testDir: './tests/scenarios',
  timeout: 120_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'report' }]],
  use: {
    baseURL: process.env.PORTAL_BASE || 'http://127.0.0.1:5173',
    screenshot: 'only-on-failure',
    video: 'off',
    trace: 'retain-on-failure',
    viewport: { width: 1440, height: 950 },
    launchOptions: { executablePath: CHROME, args: ['--no-sandbox', '--disable-dev-shm-usage'] }
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }]
})
