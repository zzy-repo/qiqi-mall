#!/usr/bin/env node
/**
 * MCP 覆盖账目生成器（零依赖，只用 node 内置模块）。
 *
 * 为什么要有它：mcp-server/README 里那段「覆盖账目」以前是**手写**的，
 * 结果是写「112 端点 / 覆盖 73」，实测 133 / 74 —— 手写的账一定会漂移。
 * 所以账目改成生成物：后端加了接口、MCP 加了工具，跑一次就对齐。
 *
 * 四步，全部是「读源码如实数」：
 *   ① 扫后端 `qiqi-mall-server/src/main/java/**` 里的
 *      `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping`，
 *      拼成「类级 @RequestMapping 前缀 + 方法级路径」的**全路径**；
 *   ② 分类：管理端业务 / 内部系统 / 红线（/admin/admins、/admin/api-keys）/ multipart / portal 端 / health；
 *   ③ 解析 MCP 侧「声称覆盖了哪些后端接口」：
 *      先读 `mcp-server/src/tools/*.mjs` 头注释里的 `GET /admin/...` 这种写法；
 *      再并上 `mcp-server/src/mall.mjs` 里 request('GET', '/admin/...') 的**真实调用路径**
 *      （注释会漏、注释会写错，真实调用不会骗人）；
 *   ④ 输出 Markdown 表格 + 一行汇总；`--check` 与 README 里 BEGIN/END 之间的内容逐字比对，不一致 exit 1。
 *
 * 用法：
 *   node scripts/mcp-coverage.mjs            # 打印生成物（stdout）
 *   node scripts/mcp-coverage.mjs --check     # 与 README 里的生成段比对；不一致 exit 1
 *   node scripts/mcp-coverage.mjs --write     # 把生成段写回 README（人肉确认后再提交）
 *
 * 约定（README 里那段必须是生成物，别手改）：
 *   BEGIN 标记行： `<!-- BEGIN mcp-coverage ...`   END 标记行：`<!-- END mcp-coverage -->`
 *   比对与写回都只认这两行之间的内容，标记行本身原样保留。
 */

import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..');
const BACKEND_SRC = path.join(REPO, 'qiqi-mall-server', 'src', 'main', 'java');
const MCP_DIR = path.join(REPO, 'mcp-server');
const TOOLS_DIR = path.join(MCP_DIR, 'src', 'tools');
const MALL_CLIENT = path.join(MCP_DIR, 'src', 'mall.mjs');
const README = path.join(MCP_DIR, 'README.md');

const BEGIN_PREFIX = '<!-- BEGIN mcp-coverage';
const END_MARK = '<!-- END mcp-coverage -->';

// ---------------------------------------------------------------- ① 后端端点

/** 递归列出目录下所有 .java（零依赖；不跟随符号链接） */
function listJavaFiles(dir) {
  const out = [];
  const walk = (d) => {
    for (const name of readdirSync(d)) {
      const full = path.join(d, name);
      const st = statSync(full);
      if (st.isDirectory()) walk(full);
      else if (name.endsWith('.java')) out.push(full);
    }
  };
  walk(dir);
  return out.sort();
}

/**
 * 去掉 Java 源码里的注释 —— 必须在抽注解**之前**做，否则 javadoc 里
 * 「刻意没有登记 @GetMapping」这类说明文字会被当成真的映射。
 * 行注释只在 `//` 前面不是 `:` 时才算（保住字符串里的 "http://"）。
 */
function stripJavaComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .split('\n')
    .map((line) => line.replace(/(^|[^:])\/\/.*$/, '$1'))
    .join('\n');
}

/** 从注解参数里抽路径字符串：`("/x")`、`(path = "/x")`、`(value = "/x")`、`({"a","b"})` */
function annotationPaths(args) {
  if (!args) return [''];
  const quoted = [...args.matchAll(/"([^"]*)"/g)].map((m) => m[1]);
  if (quoted.length === 0) return ['']; // 形如 @GetMapping（无参）= 用类级前缀
  return quoted;
}

/** 拼全路径：类级前缀 + 方法级；都容忍空串与多余斜杠 */
function joinPath(prefix, sub) {
  const p = (prefix || '').replace(/\/+$/, '');
  const s = (sub || '').replace(/^\/+/, '');
  const full = `${p}/${s}`.replace(/\/{2,}/g, '/');
  return full === '' ? '/' : full.replace(/\/$/, '') || '/';
}

function parseBackend() {
  const endpoints = [];
  for (const file of listJavaFiles(BACKEND_SRC)) {
    const raw = readFileSync(file, 'utf8');
    const src = stripJavaComments(raw);
    const rel = path.relative(BACKEND_SRC, file).split(path.sep).join('/');

    // 类级前缀：@RequestMapping("...")（本仓库没有多路径写法，遇到也只取第一条）
    const classMapping = src.match(/@RequestMapping\s*(?:\(([^)]*)\))?/);
    const prefixes = classMapping ? annotationPaths(classMapping[1]) : [''];

    const ANN = /@(Get|Post|Put|Delete|Patch)Mapping\s*(?:\(([^)]*)\))?/g;
    let m;
    while ((m = ANN.exec(src)) !== null) {
      const method = m[1].toUpperCase();
      const args = m[2];
      const subs = annotationPaths(args);

      // 处理体是否吃 MultipartFile：从注解末尾到方法体第一个 `{` 之前找签名
      const after = src.slice(ANN.lastIndex);
      const head = after.slice(0, after.indexOf('{') === -1 ? after.length : after.indexOf('{'));
      const multipart = /MultipartFile/.test(head);

      for (const prefix of prefixes) {
        for (const sub of subs) {
          endpoints.push({ method, path: joinPath(prefix, sub), multipart, file: rel });
        }
      }
    }
  }
  return endpoints;
}

// ---------------------------------------------------------------- ② 分类

const RED_LINE_PREFIXES = ['/admin/admins', '/admin/api-keys'];
const INTERNAL_PREFIXES = ['/admin/auth', '/portal/auth', '/channel-api'];

/**
 * 六类：管理端业务 / 内部系统 / 红线 / multipart / portal 端 / health。
 * 判序：health → 红线 → 内部系统 → multipart → portal → 管理端业务。
 * （multipart 单独摘出来，是因为它不是「漏做」，而是**形态不支持**：
 *   MCP 的入参是模型产出的 JSON 文本，喂不了文件流。摘出来才能让缺口数说实话。）
 */
function classify(ep) {
  if (ep.path === '/api/health') return 'health';
  if (RED_LINE_PREFIXES.some((p) => ep.path === p || ep.path.startsWith(`${p}/`))) return 'redline';
  if (INTERNAL_PREFIXES.some((p) => ep.path === p || ep.path.startsWith(`${p}/`))) return 'internal';
  if (ep.multipart) return 'multipart';
  if (ep.path === '/portal' || ep.path.startsWith('/portal/')) return 'portal';
  if (ep.path.startsWith('/admin')) return 'admin';
  return 'internal'; // 兜底：既不是 admin 也不是 portal 的接口，一律视为内部系统
}

// ---------------------------------------------------------------- ③ MCP 侧声称覆盖

/** 注释里「否定的那一行」不算声称（如「**没有** GET /admin/deliveries/{id}」） */
const NEGATIVE = /(没有|不存在|不支持|不暴露|不提供|不吃|只吃|不走|无法|刻意|禁|做不到|拿不到|不给 AI)/;

/**
 * 注释/路径常量 → 规范化的路径模式。
 * 一律去掉 query、把 `${...}` 与 `{...}` 统一成通配段、把 Java 注解里的 `{id}` 保留为通配。
 */
function normalizePattern(p) {
  let s = String(p).trim();
  s = s.split('?')[0];
  s = s.replace(/\$\{[^}]*\}/g, '\u0000'); // 模板变量 → 占位
  s = s.replace(/\{[^}]*\}/g, '\u0000'); // @PathVariable 名字也统一
  s = s.replace(/\/+$/, '');
  return s === '' ? '/' : s;
}

const isWild = (seg) => seg === '\u0000';

/** 路径 → 段数组（首段空串去掉） */
function segments(p) {
  const norm = normalizePattern(p);
  return norm === '/' ? [] : norm.split('/').slice(1);
}

/**
 * 模式 A 是否（在保守口径下）覆盖后端端点 B：
 * 段数必须相等；每段要么字面相等，要么**两边都是通配**。
 *
 * 为什么通配不能匹配字面：`GET /admin/skus/{id}`（skuDetail）若允许匹配
 * `GET /admin/skus/attributes`，就会凭空「覆盖」一个其实没做的接口。
 * 反过来 `{id}` vs `{skuId}` 两边都是通配，必须算覆盖 —— 参数名不同不影响同一端点。
 */
function covers(aSegs, bSegs) {
  if (aSegs.length !== bSegs.length) return false;
  for (let i = 0; i < aSegs.length; i += 1) {
    const a = aSegs[i];
    const b = bSegs[i];
    if (isWild(a)) {
      if (!isWild(b)) return false;
    } else if (a !== b) return false;
  }
  return true;
}

/** 注释里的写法可能把多个方法并在一起（`GET/POST/PUT/DELETE /admin/warehouses`），逐字展开 */
function expandMethodWords(word) {
  return word
    .split(/[\/|,、]/)
    .map((w) => w.trim().toUpperCase())
    .filter((w) => ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'].includes(w));
}

/** `[/{id}]` 这种「可选段」写法展开成两路：带与不带 */
function expandOptionals(p) {
  const m = p.match(/^(.*?)\[([^\]]*)\](.*)$/);
  if (!m) return [p];
  const [, before, inner, after] = m;
  const out = [];
  for (const tail of expandOptionals(after)) {
    out.push(...expandOptionals(`${before}${inner}${tail}`), ...expandOptionals(`${before}${tail}`));
  }
  return out;
}

/** 扫一个文件里所有「注释里声称的接口」 */
function claimsFromComments(file, sink) {
  const lines = readFileSync(file, 'utf8').split('\n');
  for (const line of lines) {
    if (!/^\s*(\/\/|\*|\/\*)/.test(line)) continue; // 只看注释行
    if (NEGATIVE.test(line)) continue;
    // 路径只吃 ASCII 里的「路径字符」：中文说明、全角括号、顿号一律截断，
    // 否则 `GET /admin/products/{id}（{product,gallery}）` 会把说明文字吃进路径里。
    const RE = /((?:GET|POST|PUT|DELETE|PATCH)(?:\s*[\/|,]\s*(?:GET|POST|PUT|DELETE|PATCH))*)\s+(\/(?:admin|portal|api|channel-api)[A-Za-z0-9/{}\[\]\-_.$]*)/g;
    let m;
    while ((m = RE.exec(line)) !== null) {
      for (const method of expandMethodWords(m[1])) {
        for (const p of expandOptionals(m[2])) sink.push({ method, path: p, from: path.basename(file) });
      }
    }
  }
}

/**
 * 把 mall.mjs 里的模板串切成「路径模式」。
 *
 * 两种 `${...}` 语义完全不同，必须分开：
 *   · 前面是 `/`（`/admin/products/${encodeURIComponent(id)}`）→ 那是**路径段**，留成通配；
 *   · 别的位置（`.../dispatch${qs ? `?${qs}` : ''}`）→ 那是拼在末尾的 query，整段丢掉。
 * 末尾 query 可能在模板里还套一层模板字面量（`${qs ? `?${qs}` : ''}`），所以按 `${}`
 * 的括号深度跳，别用正则去啃引号 —— 一层嵌套就会把路径截断成半截。
 */
function templateToPath(raw) {
  let out = '';
  let i = 0;
  while (i < raw.length) {
    const ch = raw[i];
    if (ch === '$' && raw[i + 1] === '{') {
      let depth = 1;
      i += 2;
      while (i < raw.length && depth > 0) {
        if (raw[i] === '{') depth += 1;
        else if (raw[i] === '}') depth -= 1;
        i += 1;
      }
      // 上面按 '{' '}' 计数，`${qs({ dryRun })}` 这种嵌套也能整段吃掉
      out += out.endsWith('/') ? '\u0000' : '\u0001'; // 路径段 / 尾部 query 标记
      continue;
    }
    if (ch === '?') break; // 真正的 query 起点
    out += ch;
    i += 1;
  }
  const tail = out.indexOf('\u0001');
  return tail >= 0 ? out.slice(0, tail) : out;
}

/** 扫 mall.mjs 里真实发出的请求：request('GET', `/admin/x/${encodeURIComponent(id)}`) */
function claimsFromMallClient(file, sink) {
  const src = readFileSync(file, 'utf8');
  const CALL = /request\(\s*'(GET|POST|PUT|DELETE|PATCH)'\s*,\s*/g;
  let m;
  while ((m = CALL.exec(src)) !== null) {
    const method = m[1];
    let i = CALL.lastIndex;
    const quote = src[i];
    if (quote !== '`' && quote !== "'" && quote !== '"') continue;
    i += 1;
    let raw = '';
    let depth = 0;
    while (i < src.length) {
      const ch = src[i];
      if (depth === 0 && ch === quote) break;
      if (ch === '\\') {
        raw += src[i + 1] ?? '';
        i += 2;
        continue;
      }
      // 花括号深度：`${...}` 里可能还套对象字面量 `${qs({ dryRun })}`，
      // 只有回到 0 才说明「引号可能结束了」
      if (ch === '{') depth += 1;
      else if (ch === '}' && depth > 0) depth -= 1;
      raw += ch;
      i += 1;
    }
    const p = templateToPath(raw);
    if (!/^\/(admin|portal|api|channel-api)/.test(p)) continue;
    sink.push({ method: method.toUpperCase(), path: p, from: 'mall.mjs' });
  }
}

function collectClaims() {
  const claims = [];
  const files = readdirSync(TOOLS_DIR).filter((f) => f.endsWith('.mjs')).sort();
  for (const f of files) claimsFromComments(path.join(TOOLS_DIR, f), claims);
  claimsFromMallClient(MALL_CLIENT, claims);
  // 去重（同一 method+path 被多处声称只算一次）
  const seen = new Set();
  return claims.filter((c) => {
    const k = `${c.method} ${normalizePattern(c.path)}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

// ---------------------------------------------------------------- ②b 结构上不给 AI 的端点

/**
 * 「这个端点对 API Key 是不是结构上就打不开」—— 不靠人手写清单，直接从
 * `ApiKeyAuthFilter` 的表里读：
 *   · `JWT_ONLY_PREFIXES`（/admin/admins、/admin/api-keys）→ 一律 403；
 *   · `ADMIN_RULES` 的读写 scope，若那个 scope 在 `unGrantableWriteScopes()` 里
 *     （ledger:write / stock:write / audit:write / channel:write / payment:write）
 *     → 它**不存在于 ALL_SCOPES**，任何 key 都拿不到 ⇒ 该端点永远 403；
 *   · 表里没登记的路径 → 过滤器兜底 403（fail-closed）。
 *
 * 这条判定比「人工维护的不给 AI 清单」可靠：清单会漂，scope 表不会 ——
 * 谁把 audit:write 加进 ALL_SCOPES，单测立刻红，账目也会立刻把 POST /admin/order-audit/run
 * 从「结构上不给 AI」翻成「真漏」。
 */
function parseScopeGate() {
  const file = path.join(BACKEND_SRC, 'com/qiqi/mall/shared/security/ApiKeyAuthFilter.java');
  const src = stripJavaComments(readFileSync(file, 'utf8'));

  const consts = new Map();
  for (const m of src.matchAll(/static final String (NO_WRITE_\w+)\s*=\s*"([^"]+)"/g)) consts.set(m[1], m[2]);

  const ungrantable = new Set(consts.values());

  const jwtOnlySrc = src.match(/JWT_ONLY_PREFIXES\s*=\s*List\.of\(([^)]*)\)/);
  const jwtOnly = jwtOnlySrc ? [...jwtOnlySrc[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]) : [];

  const rules = [];
  for (const m of src.matchAll(/new Rule\("([^"]+)",\s*"([^"]+)",\s*(?:"([^"]+)"|(\w+))\)/g)) {
    const readScope = m[2];
    const writeScope = m[3] ?? consts.get(m[4]) ?? m[4];
    rules.push({ prefix: m[1], readScope, writeScope });
  }
  rules.sort((a, b) => b.prefix.length - a.prefix.length); // 最长前缀优先（与过滤器同口径）

  const under = (p, prefix) => p === prefix || p.startsWith(`${prefix}/`);

  /** @returns {{ai:true}|{ai:false, why:string, scope?:string}} */
  return function gate(ep) {
    if (jwtOnly.some((p) => under(ep.path, p))) return { ai: false, why: '两条红线：只能人工 JWT' };
    const rule = rules.find((r) => under(ep.path, r.prefix));
    if (!rule) return { ai: false, why: '未登记 ADMIN_RULES，过滤器兜底 403' };
    const read = ep.method === 'GET' || ep.method === 'HEAD';
    const scope = read ? rule.readScope : rule.writeScope;
    if (scope === null || scope === undefined) return { ai: false, why: '该路径只对人工开放（写 scope 为 null）' };
    if (ungrantable.has(scope)) {
      return { ai: false, scope, why: `${scope} 不在 ALL_SCOPES 里（任何 key 都拿不到）` };
    }
    return { ai: true, scope };
  };
}

// ---------------------------------------------------------------- ④ 生成 Markdown

const CATEGORY_ROWS = [
  ['admin', '管理端业务', '后台 admin-web 上「人能点」的接口（AI 应当覆盖的部分）'],
  ['internal', '内部系统', '登录/验证码、买家注册，以及 mock 平台的入站回调（/channel-api）；不是后台业务操作'],
  ['redline', '红线', '`/admin/admins**` + `/admin/api-keys**`：管理员与密钥管理，API Key 一律 403'],
  ['multipart', 'multipart 形态', '只吃文件流的接口（`MultipartFile`），MCP 入参是模型产出的 JSON 文本，形态上做不到'],
  ['portal', 'portal 端', '买家端接口：读写都依赖会员身份，API Key 没有会员主语（`CurrentUser.id()` 为 null）'],
  ['health', 'health', '健康检查，不是业务接口'],
];

function buildReport() {
  const endpoints = parseBackend();
  const claims = collectClaims();
  const claimSegs = claims.map((c) => segments(c.path));

  const rows = [];
  const uncoveredAdmin = [];
  const coveredAdmin = [];

  // 每个端点标记是否被声称覆盖（只对 admin 分类统计口径有意义，其余也照实算）
  const gate = parseScopeGate();
  for (const ep of endpoints) {
    ep.cat = classify(ep);
    ep.gate = ep.cat === 'admin' ? gate(ep) : { ai: false, why: '不属于管理端业务' };
    const segs = segments(ep.path);
    const hits = claims.filter((c, i) => c.method === ep.method && covers(claimSegs[i], segs));
    ep.hits = hits;
    ep.claimed = hits.length > 0;
    // 「声称」不等于「覆盖」：结构上 403 的端点（写 scope 不存在 / 红线 / 未登记）
    // 就算注释里写了，工具也调不通 —— 那是**越权声称**，照实计进缺口并单独标出来。
    ep.covered = ep.claimed && ep.gate.ai;
    ep.overClaim = ep.claimed && !ep.gate.ai;
  }

  for (const [key, label, note] of CATEGORY_ROWS) {
    const list = endpoints.filter((e) => e.cat === key);
    const covered = list.filter((e) => e.covered);
    const gap = list.filter((e) => !e.covered);
    rows.push({ key, label, note, total: list.length, covered: covered.length, gap });
    if (key === 'admin') {
      coveredAdmin.push(...covered);
      uncoveredAdmin.push(...gap);
    }
  }

  const total = endpoints.length;
  const adminRow = rows.find((r) => r.key === 'admin');
  const sumCovered = rows.reduce((n, r) => n + r.covered, 0);
  const catCount = (k) => rows.find((r) => r.key === k)?.total ?? 0;

  const structural = uncoveredAdmin.filter((e) => !e.gate.ai);
  const realGap = uncoveredAdmin.filter((e) => e.gate.ai);
  const overClaimed = uncoveredAdmin.filter((e) => e.overClaim);

  const portalList = endpoints.filter((e) => e.cat === 'portal');
  const portalRead = portalList.filter((e) => e.method === 'GET' || e.method === 'HEAD');

  const lines = [];
  lines.push('**覆盖账目（脚本生成）**：后端 `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping`');
  lines.push(`共 **${total}** 个端点（\`qiqi-mall-server/src/main/java\` 全量扫描，类级前缀已拼上）。分类如下：`);
  lines.push('');
  lines.push('| 分类 | 端点 | MCP 已覆盖 | 缺口 | 说明 |');
  lines.push('| --- | ---: | ---: | ---: | --- |');
  for (const r of rows) {
    const notApplicable = r.key === 'admin' ? '' : '—';
    lines.push(
      `| ${r.label} | ${r.total} | ${r.key === 'admin' ? r.covered : notApplicable} | ${
        r.key === 'admin' ? r.gap.length : notApplicable
      } | ${r.note} |`
    );
  }
  lines.push(`| **合计** | **${total}** | **${sumCovered}** | **${adminRow.gap.length}** | 已覆盖只对管理端业务有「应当覆盖」的含义 |`);
  lines.push('');
  lines.push(
    `**一句话汇总**：管理端业务 **${adminRow.total}** / MCP 已覆盖 **${adminRow.covered}** / 缺口 **${adminRow.gap.length}**` +
      `（后端共 ${total} 个端点 = 管理端业务 ${catCount('admin')} + 内部系统 ${catCount('internal')} + 红线 ${catCount('redline')}` +
      ` + multipart ${catCount('multipart')} + portal 端 ${catCount('portal')} + health ${catCount('health')}）；` +
      `缺口 ${adminRow.gap.length} 个里**结构上不给 AI ${structural.length} 个、真漏 ${realGap.length} 个**` +
      (overClaimed.length ? `，其中 ${overClaimed.length} 个被 MCP 侧注释**越权声称**过（结构上 403，调不通）` : '') +
      '。'
  );
  lines.push('');
  lines.push(
    `**portal 端 ${catCount('portal')} 个端点（只读 ${portalRead.length} / 写入 ${portalList.length - portalRead.length}）**：` +
      '写接口需要买家身份，API Key 一律 403（`CurrentUser.id()` 为 null，写进去的数据无主）；' +
      '只读接口与后台读的是**同一份数据**，用管理端工具读等价（所以单列在这里，不计入「管理端业务缺口」）。'
  );
  lines.push('');
  if (uncoveredAdmin.length === 0) {
    lines.push('**管理端业务缺口：0 个** —— 后台能点的每一个读写端点都有对应 MCP 工具。');
  } else {
    lines.push(
      `**管理端业务缺口 ${uncoveredAdmin.length} 个 = 结构上不给 AI ${structural.length} + 真漏 ${realGap.length}**` +
        `（「结构上不给 AI」的判定见下：${structural.length ? '它们的写 scope 不在 ALL_SCOPES 里，任何 API Key 都 403' : '无'}）：`
    );
    lines.push('');
    lines.push('| 方法 | 路径 | 性质 | 源文件 |');
    lines.push('| --- | --- | --- | --- |');
    for (const e of uncoveredAdmin) {
      const nature = e.gate.ai
        ? '**真漏（该补工具）**'
        : `不给 AI：${e.gate.why}${e.overClaim ? '；⚠️ MCP 侧注释里提过它（越权声称，应删掉那句）' : ''}`;
      lines.push(`| ${e.method} | \`${e.path}\` | ${nature} | ${e.file} |`);
    }
  }
  lines.push('');
  lines.push(
    `口径：MCP 侧声称的接口来自 \`mcp-server/src/tools/*.mjs\` 头注释 + \`mcp-server/src/mall.mjs\` 的 ` +
      `request() 路径常量，去 query、通配 \`{id}\` 后按「方法 + 段数 + 段值」严格比对（通配只匹配通配，` +
      `避免 \`/admin/skus/{id}\` 顺手把 \`/admin/skus/attributes\` 也算掉）。重算：\`node scripts/mcp-coverage.mjs\`。`
  );
  return { text: lines.join('\n'), stats: { total, admin: adminRow.total, covered: adminRow.covered, gap: adminRow.gap.length, rows } };
}

// ---------------------------------------------------------------- README 生成段

function readBlock(md) {
  const lines = md.split('\n');
  const start = lines.findIndex((l) => l.trimStart().startsWith(BEGIN_PREFIX));
  const end = lines.findIndex((l) => l.trim() === END_MARK);
  if (start === -1 || end === -1 || end < start) return null;
  return { lines, start, end, body: lines.slice(start + 1, end).join('\n') };
}

function main() {
  const { text, stats } = buildReport();
  const mode = process.argv[2] || '';

  if (mode === '--check') {
    const md = readFileSync(README, 'utf8');
    const block = readBlock(md);
    if (!block) {
      console.error(`✗ ${path.relative(REPO, README)} 里找不到 BEGIN/END 标记（${BEGIN_PREFIX} … ${END_MARK}）`);
      process.exit(1);
    }
    const current = block.body.trim();
    const expected = text.trim();
    if (current === expected) {
      console.log(`✓ README 覆盖段与源码一致（管理端业务 ${stats.admin} / 已覆盖 ${stats.covered} / 缺口 ${stats.gap}）`);
      process.exit(0);
    }
    const curLines = current.split('\n');
    const expLines = expected.split('\n');
    console.error('✗ README 覆盖段已漂移（本段是生成物，别手改）。差异（- README 现状 / + 源码生成）：');
    const max = Math.max(curLines.length, expLines.length);
    let shown = 0;
    for (let i = 0; i < max && shown < 40; i += 1) {
      if (curLines[i] === expLines[i]) continue;
      console.error(`  L${i + 1} - ${curLines[i] ?? '(缺行)'}`);
      console.error(`  L${i + 1} + ${expLines[i] ?? '(缺行)'}`);
      shown += 1;
    }
    console.error('修复：node scripts/mcp-coverage.mjs --write');
    process.exit(1);
  }

  if (mode === '--write') {
    const md = readFileSync(README, 'utf8');
    const block = readBlock(md);
    if (!block) {
      console.error(`✗ ${path.relative(REPO, README)} 里找不到 BEGIN/END 标记（${BEGIN_PREFIX} … ${END_MARK}）`);
      process.exit(1);
    }
    const next = [...block.lines.slice(0, block.start + 1), ...text.split('\n'), ...block.lines.slice(block.end)].join('\n');
    writeFileSync(README, next);
    console.log(`✓ 已写回 ${path.relative(REPO, README)}（管理端业务 ${stats.admin} / 已覆盖 ${stats.covered} / 缺口 ${stats.gap}）`);
    process.exit(0);
  }

  // 生成物原样打到 stdout（**只打这一段**：它就是 README 里 BEGIN/END 之间的内容，
  // 多打一行汇总会让 --check 永远红）
  console.log(text);
}

main();
