import { defineConfig } from '@rspress/core';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { readdirSync, readFileSync } from 'node:fs';
import sidebar from './sidebar.generated.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));

/**
 * 口语稿 guard —— 拦的是「跳过 npm run build、直接 rspress build」这条路。
 *
 * 正经构建是 `npm run build`：先 `gen:prod` 把口语稿**根本不写出来**，再 build。
 * 但如果有人手跑 `npx rspress build` 而内容根还停在 dev 状态，口语稿就会被烤进产物；
 * 那 74 份稿子是作者面试口述用的私人材料，与 PRIVATE_DOCS 那 12 篇同一个原则，不能上公网。
 * 所以构建时按**内容字节**再扫一遍，命中就直接失败（fail-closed，不靠人记得）。
 *
 * 例外只有内网完整版：`npm run build:dev` 会置 RSPRESS_ALLOW_SPOKEN=1（产物进 doc_build_dev，
 * 不出网、且 check-docs-site.mjs 对它用 --private-ok 显式豁免）。
 */
const IS_BUILD = process.argv.includes('build');
if (IS_BUILD && process.env.RSPRESS_ALLOW_SPOKEN !== '1') {
  const hits = []
  const walk = (rel = '') => {
    let ents = []
    try { ents = readdirSync(join(HERE, 'doc', rel), { withFileTypes: true }) } catch { return }
    for (const e of ents) {
      const child = rel ? `${rel}/${e.name}` : e.name
      if (e.isDirectory()) { walk(child); continue }
      if (!e.name.endsWith('.md')) continue
      const text = readFileSync(join(HERE, 'doc', child), 'utf8')
      if (/spoken-script|口语稿/.test(text)) hits.push(child)
    }
  }
  walk()
  if (hits.length) {
    throw new Error(
      `[spoken-guard] 内容根里有 ${hits.length} 个页面仍嵌着口语稿（${hits.slice(0, 3).join(', ')}${hits.length > 3 ? ' …' : ''}）。\n`
      + `  口语稿只允许出现在内网 dev 产物里。正确做法：\n`
      + `    npm run build        # = gen:prod（不产出口语稿） + rspress build\n`
      + `    npm run build:dev    # 内网完整版，产物进 doc_build_dev\n`,
    )
  }
}

/**
 * qiqi-mall 主文档站（Rspress 版）
 *
 * 内容根 doc/ 与 sidebar.generated.mjs **全部由生成器产出**，不要手改：
 *   node build-material.mjs --target=rspress --mode=dev|prod   （真身 = /root/docsify/build-material.mjs）
 *
 * 为什么侧边栏用 config 里的生成数组、而不用 _meta.json：
 *   _meta.json 只能反映目录层级（material → scenarios → 01-买家侧），而侧边栏要的是
 *   `**项目材料** → 一、功能场景（图 + 视频） → 买家侧` 这种与目录不同构的语义层，
 *   且分组名（「二、状态机」）与目录名（01-状态机）本就不同 —— 用目录树表达不出来。
 */
export default defineConfig({
  root: 'doc',
  // ★ cleanUrls 必须显式关掉（Rspress 2.x 默认 true）：公网是**纯 OSS 静态托管**，
  //   没有 URI 重写，产物只有 xxx.html。默认 true 时站内链接渲染成不带 .html 的
  //   "干净 URL" ⇒ 点侧栏能进（客户端路由），但**在页面上按 F5 / 把地址栏 URL 发给别人
  //   就是 404（OSS NoSuchKey）**。实测过：open /material/.../1-全库总览 → 页面正常，
  //   reload → 404。改成 false 后链接与产物对齐为 xxx.html，刷新/分享/直达全部正常；
  //   配 cleanUrlsRedirect（默认 true）让手输的干净 URL 在浏览器侧归一到 .html。
  //   代价：链接变长（带 .html），没有别的副作用。改这条要重跑 12 项门禁再发。
  route: { cleanUrls: false },
  // dev / prod 两个产物目录：doc_build = prod（要发布的那个），doc_build_dev = 内网完整版
  outDir: process.env.RSPRESS_OUT || 'doc_build',
  title: '七七商城 · 项目文档',
  description: '七七商城项目文档站：功能场景录屏、状态机/时序/架构与数据模型图解。',
  // lang 必须是 'zh'：Rspress 内置文案表按 'zh' 键索引，写 'zh-CN' 会整体回退到英文
  // （上一版实测：菜单变成 Menu / ON THIS PAGE / Copy Markdown / Next page）
  lang: 'zh',
  // sitemap 里的绝对前缀，别让它落成 http://localhost:3000
  siteOrigin: process.env.DOCS_SITE_ORIGIN || 'https://docs.rose7.cn',
  // globalStyles 必须是**绝对路径**：它是被当作 import 源注入 .rspress/runtime/virtual-global-styles.js 的，
  // 相对路径会从那个虚拟模块所在目录解析 → Module not found（实测报错）
  globalStyles: join(HERE, 'theme/custom.css'),
  // 旧链接兜底脚本要注入**每一页**，不能只放 404：
  // docsify 的 #/material/… 打到新站时，浏览器只请求 "/"，命中首页 200，压根不会进 404 页。
  // 脚本本体由生成器产出（doc/public/legacy-redirect.js），这里只负责挂上去。
  head: [['script', { src: '/legacy-redirect.js', defer: '' }]],
  // 404 文案：Rspress 对未知路径渲染的是主题的 NotFound 布局（自建 /404 路由的内容会被 hydration 覆盖掉），
  // 所以人话提示只能从这里改。键名来自主题内置文案表：notFoundText / takeMeHomeText。
  // 形状是「文案键 → { 语言: 文案 }」，不是「语言 → { 键: 文案 }」（写反了构建会报
  // [i18n] i18n key zh has no text for lang zh）
  i18nSource: {
    notFoundText: { zh: '页面不存在 · 旧链接已改版，请从左侧目录进入', en: 'Page not found' },
    takeMeHomeText: { zh: '回到首页', en: 'Back home' },
  },
  themeConfig: {
    nav: [{ text: '首页', link: '/' }],
    sidebar: { '/': sidebar },
    // 关掉页面上那颗「Copy Markdown」按钮：llms.txt 与每页 .md 镜像照常生成，只是不占版面
    llmsUI: false,
    outlineTitle: '本页目录',
    lastUpdated: false,
    editLink: undefined,
    footer: { message: '七七商城 · 项目文档（Rspress）' },
  },
});
