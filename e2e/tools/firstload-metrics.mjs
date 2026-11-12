#!/usr/bin/env node
/**
 * 首屏加载指标测量脚本（可重复运行）
 *
 * 用途：给「首屏加载优化」建立可量化基线 —— 改 Caddy 之前跑一次，改完再跑一次，
 *      两次 JSON 对比即可算出每一项的真实收益。
 *
 * 用法：
 *   node e2e/tools/firstload-metrics.mjs --out /root/firstload-baseline.json
 *   node e2e/tools/firstload-metrics.mjs --out /root/firstload-after-caddy.json
 *   node e2e/tools/firstload-metrics.mjs --runs 3 --sites mall,admin   # 冒烟测试用
 *
 * 为什么放 e2e/ 下：playwright 装在 e2e/node_modules，脚本要能 import 'playwright'。
 *
 * 关键设计：
 *  - 每次测量都用全新的 browser context ⇒ 冷缓存，测出来的才是「首次访客」体验。
 *  - 每个站点测 N 次（默认 3）取中位数，抵消单次抖动。
 *  - 额外在同一个 context 里 reload 一次，记录「二次访问」传输字节：
 *    配上 immutable 长缓存后这个数字会塌到接近 0，是长缓存生效的直接证据。
 *  - 串行执行（一个站点一个 context，用完即关）：本机 swap 只剩 ~200MB，
 *    绝不能同时开多个 chromium/页面。
 */

import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';

// headless_shell-1254 没装，必须显式指定已安装的 chromium 可执行文件，
// 否则直接 launch() 会报 "Executable doesn't exist"。
const CHROME_PATH = '/root/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome';

const SITES = [
    { key: 'mall', url: 'https://mall.rose7.cn/', auth: false },
    { key: 'admin', url: 'https://admin.rose7.cn/', auth: true },
];

// admin 目前挂了一层 basic_auth（过渡期，后续会去掉）。
// 允许用环境变量覆盖，避免把口令硬编码进任何输出。
const ADMIN_CREDS = {
    username: process.env.E2E_ADMIN_USER || 'qiqi',
    password: process.env.E2E_ADMIN_PASS || 'qiqi2026',
};

// ---------- 参数解析 ----------
function parseArgs(argv) {
    const opts = { out: '/root/firstload-baseline.json', runs: 3, sites: null };
    for (let i = 2; i < argv.length; i++) {
        const a = argv[i];
        if (a === '--out') opts.out = argv[++i];
        else if (a === '--runs') opts.runs = Math.max(1, parseInt(argv[++i], 10) || 3);
        else if (a === '--sites') opts.sites = argv[++i].split(',').map(s => s.trim()).filter(Boolean);
        else if (a === '--help' || a === '-h') {
            console.log('usage: node e2e/tools/firstload-metrics.mjs [--out FILE] [--runs N] [--sites mall,admin]');
            process.exit(0);
        }
    }
    return opts;
}

// ---------- 页面内采集 ----------
// 在浏览器上下文里跑：只用标准 Performance API，拿到的都是真实网络时间轴。
function collectInPage() {
    const nav = performance.getEntriesByType('navigation')[0] || {};
    const paints = performance.getEntriesByType('paint');
    const fcpEntry = paints.find(p => p.name === 'first-contentful-paint');
    const res = performance.getEntriesByType('resource');

    const sumTransfer = res.reduce((s, r) => s + (r.transferSize || 0), 0);

    // transferSize=0 表示命中缓存（本地缓存不产生网络传输）——二次访问的证据。
    const cached = res.filter(r => (r.transferSize || 0) === 0).length;

    return {
        // responseStart = 收到首字节的时间点（相对 navigationStart），即 TTFB
        ttfb: nav.responseStart != null ? Math.round(nav.responseStart) : null,
        fcp: fcpEntry ? Math.round(fcpEntry.startTime) : null,
        dcl: nav.domContentLoadedEventEnd != null ? Math.round(nav.domContentLoadedEventEnd) : null,
        load: nav.loadEventEnd != null ? Math.round(nav.loadEventEnd) : null,
        reqCount: res.length,
        // 传输字节总数 = 文档 + 全部子资源
        transferTotal: sumTransfer + (nav.transferSize || 0),
        htmlTransfer: nav.transferSize || 0,
        htmlEncoded: nav.encodedBodySize || 0,
        htmlDecoded: nav.decodedBodySize || 0,
        cachedResources: cached,
        resources: res.map(r => ({
            file: r.name.split('/').pop().split('?')[0],
            url: r.name,
            type: r.initiatorType,
            transfer: r.transferSize || 0,
            encoded: r.encodedBodySize || 0,
            decoded: r.decodedBodySize || 0,
        })),
    };
}

const median = (arr) => {
    const v = arr.filter(x => typeof x === 'number' && Number.isFinite(x)).sort((a, b) => a - b);
    if (!v.length) return null;
    const m = Math.floor(v.length / 2);
    return v.length % 2 ? v[m] : Math.round((v[m - 1] + v[m]) / 2);
};

function medianOf(runs, field) {
    return median(runs.map(r => r[field]));
}

// ---------- 单站点测量 ----------
async function measureSite(browser, site, runs) {
    const coldRuns = [];
    const warmRuns = [];
    const errors = [];

    for (let i = 1; i <= runs; i++) {
        // 每次一个全新 context ⇒ 空缓存、空 cookie、空 storage，等价于首次访客
        const ctx = await browser.newContext({
            httpCredentials: site.auth ? ADMIN_CREDS : undefined,
            ignoreHTTPSErrors: false,
            viewport: { width: 1366, height: 768 },
        });
        const page = await ctx.newPage();
        try {
            // ---- 首次访问（冷缓存） ----
            await page.goto(site.url, { waitUntil: 'load', timeout: 90000 });
            // 等 load 后再留一点时间让 paint 条目落库（FCP 是异步上报的）
            await page.waitForTimeout(1200);
            const cold = await page.evaluate(collectInPage);
            coldRuns.push(cold);

            // ---- 二次访问（同 context，缓存已热） ----
            await page.reload({ waitUntil: 'load', timeout: 90000 });
            await page.waitForTimeout(1200);
            const warm = await page.evaluate(collectInPage);
            warmRuns.push(warm);

            console.log(
                `  ${site.key} run ${i}/${runs}: TTFB=${cold.ttfb}ms FCP=${cold.fcp}ms ` +
                `load=${cold.load}ms req=${cold.reqCount} transfer=${(cold.transferTotal / 1024).toFixed(0)}KB ` +
                `| warm transfer=${(warm.transferTotal / 1024).toFixed(0)}KB cached=${warm.cachedResources}/${warm.reqCount}`
            );
        } catch (e) {
            errors.push(`run ${i}: ${e.message}`);
            console.log(`  ${site.key} run ${i}/${runs}: FAILED - ${e.message}`);
        } finally {
            await ctx.close(); // 立刻关掉，别让多个页面同时占内存
        }
    }

    const m = {
        ttfb: medianOf(coldRuns, 'ttfb'),
        fcp: medianOf(coldRuns, 'fcp'),
        dcl: medianOf(coldRuns, 'dcl'),
        load: medianOf(coldRuns, 'load'),
        reqCount: medianOf(coldRuns, 'reqCount'),
        transferTotal: medianOf(coldRuns, 'transferTotal'),
        htmlTransfer: medianOf(coldRuns, 'htmlTransfer'),
    };
    const warm = {
        ttfb: medianOf(warmRuns, 'ttfb'),
        fcp: medianOf(warmRuns, 'fcp'),
        dcl: medianOf(warmRuns, 'dcl'),
        load: medianOf(warmRuns, 'load'),
        reqCount: medianOf(warmRuns, 'reqCount'),
        transferTotal: medianOf(warmRuns, 'transferTotal'),
        htmlTransfer: medianOf(warmRuns, 'htmlTransfer'),
        cachedResources: medianOf(warmRuns, 'cachedResources'),
    };

    // 最大的几个资源：压缩前后字节对比（compression ratio 的浏览器视角证据）
    const biggest = (coldRuns[0]?.resources || [])
        .slice()
        .sort((a, b) => b.decoded - a.decoded)
        .slice(0, 6)
        .map(r => ({
            file: r.file,
            decodedBytes: r.decoded,
            transferBytes: r.transfer,
            // wire ratio：真正上网络的比例（含 header 开销，故接近 1 表示没压缩）
            wireRatio: r.decoded ? +(r.transfer / r.decoded).toFixed(3) : null,
        }));

    return { site: site.key, url: site.url, runs: coldRuns.length, coldRuns, warmRuns, median: m, medianWarm: warm, biggestResources: biggest, errors };
}

// ---------- 输出表格 ----------
const kb = (n) => (n == null ? '-' : (n / 1024).toFixed(0));

function printTable(results) {
    const line = (a) => console.log(a);
    line('');
    line('═'.repeat(96));
    line('  首屏加载指标（每站点 ' + results.runs + ' 次取中位数，全新 context = 冷缓存）');
    line('═'.repeat(96));
    line(
        '  站点'.padEnd(8) +
        'TTFB'.padStart(9) + 'FCP'.padStart(10) + 'DCL'.padStart(10) + 'load'.padStart(10) +
        '请求数'.padStart(8) + '传输KB'.padStart(10) + 'HTML KB'.padStart(10) + '二次访问KB'.padStart(13)
    );
    line('  ' + '─'.repeat(94));
    for (const r of results.sites) {
        const c = r.median, w = r.medianWarm;
        line(
            ('  ' + r.site).padEnd(8) +
            ((c.ttfb ?? '-') + 'ms').padStart(9) +
            ((c.fcp ?? '-') + 'ms').padStart(10) +
            ((c.dcl ?? '-') + 'ms').padStart(10) +
            ((c.load ?? '-') + 'ms').padStart(10) +
            String(c.reqCount ?? '-').padStart(8) +
            kb(c.transferTotal).padStart(10) +
            (kb(c.htmlTransfer) + 'KB').padStart(10) +
            (kb(w.transferTotal) + 'KB').padStart(13)
        );
    }
    line('═'.repeat(96));

    for (const r of results.sites) {
        if (!r.biggestResources.length) continue;
        line('');
        line(`  最大资源（${r.site}，decoded = 解压后原始字节 / transfer = 实际过网字节）:`);
        for (const b of r.biggestResources) {
            line(
                '    ' + b.file.padEnd(34).slice(0, 34) +
                String((b.decodedBytes / 1024).toFixed(0) + 'KB').padStart(11) + ' → ' +
                String((b.transferBytes / 1024).toFixed(0) + 'KB').padStart(9) +
                '   wire ratio ' + String(b.wireRatio).padStart(6)
            );
        }
        if (r.errors.length) {
            line(`  ⚠ ${r.site} 有 ${r.errors.length} 次失败：${r.errors.join(' | ')}`);
        }
    }
    line('');
}

// ---------- main ----------
const opts = parseArgs(process.argv);
const targets = opts.sites ? SITES.filter(s => opts.sites.includes(s.key)) : SITES;

if (!fs.existsSync(CHROME_PATH)) {
    console.error(`找不到 chromium: ${CHROME_PATH}`);
    process.exit(1);
}

console.log(`chromium: ${CHROME_PATH}`);
console.log(`站点: ${targets.map(t => t.url).join(', ')}  次数: ${opts.runs}`);

const browser = await chromium.launch({
    executablePath: CHROME_PATH,
    headless: true,
    // 单进程少占内存；本机 swap 紧张
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'],
});

const results = { generatedAt: new Date().toISOString(), chrome: CHROME_PATH, runs: opts.runs, sites: [] };
try {
    for (const site of targets) {
        console.log(`\n▶ ${site.key}  ${site.url}`);
        results.sites.push(await measureSite(browser, site, opts.runs));
    }
} finally {
    await browser.close(); // 无论成功失败都必须关掉 chromium
    console.log('\nchromium 已关闭');
}

printTable(results);

fs.mkdirSync(path.dirname(opts.out), { recursive: true });
fs.writeFileSync(opts.out, JSON.stringify(results, null, 2));
console.log(`JSON 已写入: ${opts.out}`);
