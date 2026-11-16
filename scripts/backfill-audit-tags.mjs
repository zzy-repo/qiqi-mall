#!/usr/bin/env node
/**
 * backfill-audit-tags.mjs —— T4：给「该有结论标却没有」的单补标（一次性纠偏，幂等，可反复重跑）
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 为什么要有它
 *   标是本期才落地的，之前判过的单身上没有标。而队列的「按标筛选」（?tag=NO_POLICY_MATCH）
 *   只认 active 标 —— 于是那些单在按标筛时**永远筛不出来**，人以为"没有这种单"，其实是"标没落"。
 *   把决策行里**已经存在的 result** 物化成一条标，是「把已有事实变成可查」，不是发明事实；
 *   属于"篡改"的是另一些事：改写 reason 原文 / 伪造没发生过的判定 / 改结论 —— 本脚本一个字都不改。
 *
 * 只补哪一类（范围写死，别扩大）
 *   ✅ 仍在待审核（order.status = 7）+ 有决策行 + 没有任何 active 的「结论」档标
 *      —— 这正是对账端点的 pendingTagMissing（真缺口：引擎该落没落）。
 *   ❌ 已离开待审核的单（status 8 / 4 / 5 / 9 …）**一条都不补**：它们的结论标在离开时被结算清空，
 *      那是 T3 刚确定的设计（「过没过」由状态表达，不由标表达）。给它们补"已通过/不通过"或
 *      补一个"无策略命中"，等于把用户 2026-11-16 16:38 否决的东西重新塞回去。
 *   脚本的 SQL 与自检都按这条边界写：任何"顺手多补一点"的改动都会在自检里露出来。
 *
 * 补什么码（映射只有一处：ConclusionTags#codeOf）
 *   脚本**不内嵌映射表**。它先调 GET /admin/order-audit/conclusion-tag-mapping 把
 *   「result(+downgrade_reason) → 标码」从运行中的服务取下来（那个端点是 ConclusionTags#codeOf
 *   的枚举投影，见 ConclusionTagMapping 的类注释），再拿它对 SQL 行。目的很简单：
 *   引擎改了映射（V9 就把 AUTO_PASS 的"已通过"标收回了），脚本立刻跟着变，补不出旧口径的标。
 *   · AUTO_PASS / SKIPPED → code = null ⇒ **不补**（前者用户拍板"通过由状态表达"，后者不写决策行）
 *
 * 每条补的标长什么样（三样东西缺一不可）
 *   reason      = 「补标：依据 run_id=<run>/decision_id=<id>；」+ **决策行里已有的判定理由原文**
 *                 （原文只追加前缀、不改写；超出 255 从尾部裁 —— 机器要断言的靠前，见仓库约定）
 *   released_by = SYSTEM:BACKFILL_V10（active 行本列为空 ⇒ "有值 = 这条标是补的"；但下一次 release
 *                 会覆盖它，所以 provenance 必须在 reason 里也有一份）
 *   反查关系     = decision_id 就在 reason 里 ⇒ 「这条标从哪条判定来的」永远查得回来
 *
 * 用法
 *   node scripts/backfill-audit-tags.mjs                    # dry-run（默认）：只打印将做什么
 *   node scripts/backfill-audit-tags.mjs --apply --yes      # 真跑（会先 dump 六个库）
 *   node scripts/backfill-audit-tags.mjs --apply --yes --db=qiqi_mall_test
 *   node scripts/backfill-audit-tags.mjs --skip-dump --apply --yes     # 已 dump 过、只重跑（验证幂等）
 *
 * 参数
 *   --dry-run            只看计划（默认；与 --apply 互斥）
 *   --apply              真写库；必须再给 --yes（补标是写历史的动作，不许手滑）
 *   --db=<a,b,...>       目标库（默认六个：qiqi_mall / qiqi_mall_test / qiqi_mall_it / qiqi_mall_e2e_a..c）
 *   --server=<url>       取映射用的 api 基址（默认 http://127.0.0.1:18080 网关）
 *   --token=<jwt>        直接给 token（否则用 --password / ADMIN_PASSWORD / /root/.qiqi-secrets/admin.txt 登录）
 *   --token-map=<url=jwt,…> 逐基址给 token（dev 栈与容器栈的 JWT 密钥不同时用；键是基址，末尾斜杠可省）
 *   --api-map=<db=url,…> 给「对账端点 / 按标筛增量」两项自检用的后端（默认 qiqi_mall=:8081、qiqi_mall_test=:18080；
 *                        没配的库跳过这两项并**明说跳过**，不假装通过）
 *   --dump-dir=<dir>     dump 目录（默认 /root/db-dumps/<YYYY-MM-DD-HHmm>-t4-backfill）
 *   --skip-dump          不 dump（⚠ 只给"确认过库没变"的重跑用）
 *   --sample=<n>         计划/结果里抽样打印几条（默认 5）
 *
 * 退出码：0 = 计划为空 或 补完且自检全绿；1 = 自检失败 / 参数错 / 有不可自动判定的行
 */
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** 默认六个库（T4 任务点名的范围；qiqi_mall_e2e_d 结构里还没有审核表，不在范围） */
const DEFAULT_DBS = ['qiqi_mall', 'qiqi_mall_test', 'qiqi_mall_it',
    'qiqi_mall_e2e_a', 'qiqi_mall_e2e_b', 'qiqi_mall_e2e_c'];

/** 有后端在读的库 → 基址（「对账端点」与「按标筛条数增量」两项自检要用） */
const DEFAULT_API_MAP = {
    qiqi_mall: 'http://127.0.0.1:8081',
    qiqi_mall_test: 'http://127.0.0.1:18080',
};

// ───────────────────────────────────────────────────────────── 参数

const argv = process.argv.slice(2);
const arg = (name, dflt = null) => {
    const hit = argv.find((a) => a === `--${name}` || a.startsWith(`--${name}=`));
    if (!hit) return dflt;
    const eq = hit.indexOf('=');
    return eq === -1 ? true : hit.slice(eq + 1);
};
const flag = (name) => argv.includes(`--${name}`);

if (flag('help') || argv.includes('-h')) {
    console.log(readFileSync(fileURLToPath(import.meta.url), 'utf8').split('*/\nimport')[0]);
    process.exit(0);
}

const APPLY = flag('apply');
const DRY = !APPLY;
const YES = flag('yes');
const SKIP_DUMP = flag('skip-dump');
const SAMPLE = Number(arg('sample', 5));
const SERVER = String(arg('server', 'http://127.0.0.1:18080')).replace(/\/+$/, '');
const DBS = String(arg('db', DEFAULT_DBS.join(','))).split(',').map((s) => s.trim()).filter(Boolean);
const API_MAP = { ...DEFAULT_API_MAP };
for (const pair of String(arg('api-map', '')).split(',').filter(Boolean)) {
    const i = pair.indexOf('=');
    if (i < 0) continue;
    API_MAP[pair.slice(0, i).trim()] = pair.slice(i + 1).trim().replace(/\/+$/, '');
}
const DUMP_DIR = String(arg('dump-dir', resolve('/root/db-dumps', `${stamp()}-t4-backfill`)));

function stamp() {
    const d = new Date();
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`;
}

if (APPLY && !YES) {
    console.error('✗ --apply 必须搭配 --yes（补标是写历史的动作）。先跑一次 --dry-run 看计划。');
    process.exit(1);
}

// ───────────────────────────────────────────────────────────── mysql 通道

const MYSQL_CONTAINER = process.env.MYSQL_CONTAINER || 'qiqi-mysql';
const DB_USER = process.env.MYSQL_USER || 'root';
const DB_PASS = process.env.MYSQL_PASSWORD || 'qiqi123456';

function haveDocker() {
    try {
        execFileSync('docker', ['inspect', '-f', '{{.State.Running}}', MYSQL_CONTAINER],
            { stdio: ['ignore', 'pipe', 'ignore'] });
        return true;
    } catch {
        return false;
    }
}
const VIA_DOCKER = haveDocker();

/** 跑一条 SQL，返回二维数组（-N -B：无表头、tab 分隔；NULL 仍是字面 'NULL'） */
function sql(db, query) {
    // 口令走 MYSQL_PWD：写在命令行会被 mysqld 客户端打一句 "Using a password ... can be insecure"，
    // 每次查询一行噪声，真出错时就淹在噪声里了
    const bin = VIA_DOCKER ? 'docker' : 'mysql';
    const args = VIA_DOCKER
        ? ['exec', '-i', '-e', `MYSQL_PWD=${DB_PASS}`, MYSQL_CONTAINER, 'mysql', `-u${DB_USER}`,
            '--default-character-set=utf8mb4', '-N', '-B', '--raw', db]
        : [`-u${DB_USER}`, '-h127.0.0.1', '--default-character-set=utf8mb4', '-N', '-B', '--raw', db];
    const out = execFileSync(bin, args, { input: query, encoding: 'utf8', env: { ...process.env, MYSQL_PWD: DB_PASS } });
    return out.split('\n').filter((l) => l.length > 0).map((l) => l.split('\t'));
}

const num = (v) => (v === undefined || v === null || v === '' || v === 'NULL' ? 0 : Number(v));
const str = (v) => (v === undefined || v === null || v === 'NULL' ? '' : v);
const q = (s) => `'${String(s).replace(/\\/g, '\\\\').replace(/'/g, "''").replace(/[\r\n]+/g, ' ')}'`;

function exists(p) {
    try { statSync(p); return true; } catch { return false; }
}

// ───────────────────────────────────────────────────────────── api（映射与两项自检）

async function apiFetch(base, path, token) {
    const res = await fetch(`${base}${path}`, { headers: { Authorization: `Bearer ${token}` } });
    const body = await res.json();
    if (body.code !== 0) throw new Error(`${path} → code=${body.code} ${body.message}`);
    return body.data;
}

/** 逐基址的 token（不同栈的 JWT 密钥可能不同：dev 用 dev 密钥、容器栈用 .env 的 JWT_SECRET） */
const TOKEN_MAP = {};
for (const pair of String(arg('token-map', '')).split(',').filter(Boolean)) {
    const i = pair.indexOf('=');
    if (i < 0) continue;
    TOKEN_MAP[pair.slice(0, i).trim().replace(/\/+$/, '')] = pair.slice(i + 1).trim();
}

const tokenCache = new Map();
async function login(base) {
    if (tokenCache.has(base)) return tokenCache.get(base);
    const explicit = TOKEN_MAP[base] || arg('token', null);
    const password = String(arg('password', process.env.ADMIN_PASSWORD
        || (exists('/root/.qiqi-secrets/admin.txt') ? readFileSync('/root/.qiqi-secrets/admin.txt', 'utf8').trim() : '')));
    let token = explicit ? String(explicit) : null;
    if (!token) {
        if (!password) throw new Error('缺少 admin 口令：设 ADMIN_PASSWORD / 写 /root/.qiqi-secrets/admin.txt / 给 --token');
        const res = await fetch(`${base}/admin/auth/login`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: arg('user', 'admin'), password }),
        });
        const body = await res.json();
        if (body.code !== 0) throw new Error(`登录失败（${base}）：${body.message}`);
        token = body.data.accessToken;
    }
    tokenCache.set(base, token);
    return token;
}

/** 映射表压成 key → code（MANUAL 按 downgrade_reason 分三格；其余 result 一格） */
function mapperOf(mapping) {
    const byKey = new Map(mapping.entries.map((e) => [`${e.result}|${e.downgradeReason ?? ''}`, e.code]));
    return {
        marker: mapping.backfillReleasedBy,
        frozenPrefix: mapping.frozenFamilyPrefix,
        entries: mapping.entries,
        codeOf: (result, downgrade) => {
            const key = `${result}|${downgrade ?? ''}`;
            return byKey.has(key) ? { known: true, code: byKey.get(key) } : { known: false, code: null };
        },
    };
}

// ───────────────────────────────────────────────────────────── 候选（该补）与现状

const CANDIDATES_SQL = `
SELECT o.id, o.order_sn, o.status, d.id, d.run_id, d.round_no, d.result,
       COALESCE(d.downgrade_reason, ''),
       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(d.evidence_json, '$.reason')), ''),
       COALESCE((SELECT GROUP_CONCAT(t.tag_code ORDER BY t.id)
                   FROM order_audit_tag t WHERE t.order_id = o.id), '')
FROM \`order\` o
JOIN order_audit_decision d
  ON d.id = (SELECT MAX(x.id) FROM order_audit_decision x WHERE x.order_id = o.id)
WHERE o.status = 7
  AND NOT EXISTS (
      SELECT 1 FROM order_audit_tag t
      WHERE t.order_id = o.id AND t.active = 1
        AND (t.tag_code LIKE 'FROZEN%'
             OR EXISTS (SELECT 1 FROM audit_tag_catalog c
                        WHERE c.code = t.tag_code AND c.category = '结论')))
ORDER BY o.id`;

function candidates(db) {
    return sql(db, CANDIDATES_SQL).map((r) => ({
        orderId: num(r[0]), orderSn: str(r[1]), status: num(r[2]),
        decisionId: num(r[3]), runId: num(r[4]), roundNo: num(r[5]),
        result: str(r[6]), downgrade: str(r[7]), reasonText: str(r[8]),
        allTagCodes: str(r[9]) ? str(r[9]).split(',') : [],
    }));
}

const statusDistribution = (db) =>
    sql(db, 'SELECT status, COUNT(*) FROM `order` GROUP BY status ORDER BY status')
        .map((r) => `${num(r[0])}:${num(r[1])}`).join(' ');

/** 已离开待审核的单身上的 active 标数 —— 补标前后必须一模一样（T3 边界自检） */
const activeTagsOutsidePending = (db) => num(sql(db, `SELECT COUNT(*)
    FROM order_audit_tag t JOIN \`order\` o ON o.id = t.order_id
    WHERE t.active = 1 AND o.status <> 7`)[0]?.[0]);

const markerCount = (db, marker) =>
    num(sql(db, `SELECT COUNT(*) FROM order_audit_tag WHERE released_by = ${q(marker)}`)[0]?.[0]);

// ───────────────────────────────────────────────────────────── dump

function dump(db) {
    mkdirSync(DUMP_DIR, { recursive: true });
    const file = resolve(DUMP_DIR, `${db}.sql`);
    const common = ['--default-character-set=utf8mb4', '--single-transaction', '--routines', '--triggers', '--events'];
    const env = { ...process.env, MYSQL_PWD: DB_PASS };
    const out = VIA_DOCKER
        ? execFileSync('docker', ['exec', '-e', `MYSQL_PWD=${DB_PASS}`, MYSQL_CONTAINER, 'mysqldump',
            `-u${DB_USER}`, ...common, '--set-gtid-purged=OFF', db], { encoding: 'utf8', maxBuffer: 1 << 30 })
        // 宿主机的 mysqldump 是 MariaDB 版：没有 --set-gtid-purged，别传
        : execFileSync('mysqldump', [`-u${DB_USER}`, '-h127.0.0.1', ...common, db],
        { encoding: 'utf8', maxBuffer: 1 << 30, env });
    writeFileSync(file, out);
    const bytes = statSync(file).size;
    if (bytes < 1024 || !out.includes('-- Dump completed')) {
        throw new Error(`dump 不完整（${bytes} 字节），拒绝继续：${file}`);
    }
    return { file, bytes };
}

// ───────────────────────────────────────────────────────────── 主流程

const pad = (s, n) => String(s ?? '').padEnd(n);
const cut = (s, n) => (String(s ?? '').length <= n ? String(s ?? '') : `${String(s).slice(0, n - 1)}…`);

async function main() {
    console.log('══════ T4 补标：该有结论标却没有的单 ══════');
    console.log(`模式：${DRY ? 'DRY-RUN（不写库）' : 'APPLY（真写库）'}    目标库：${DBS.join(', ')}`);
    console.log(`mysql 通道：${VIA_DOCKER ? `docker exec ${MYSQL_CONTAINER}` : '本机 mysql 客户端'}`);

    // ① 映射：唯一出处 ConclusionTags#codeOf（经只读端点取，脚本不内嵌）
    const map = mapperOf(await apiFetch(SERVER, '/admin/order-audit/conclusion-tag-mapping', await login(SERVER)));
    console.log(`\n── ① 映射（来自 ${SERVER}/admin/order-audit/conclusion-tag-mapping）──`);
    for (const e of map.entries) {
        console.log(`   ${pad(e.result, 10)} ${pad(e.downgradeReason ?? '(—)', 14)} → ${e.code ?? '(不落标)'}`);
    }
    for (const n of map.notes ?? []) console.log(`   · ${n}`);
    console.log(`   补标来源标记：${map.marker}`);

    // ② 计划
    const plans = [];
    console.log('\n── ② 计划（「仍在待审核 + 有决策行 + 无 active 结论标」才补）──');
    for (const db of DBS) {
        const dumped = APPLY && !SKIP_DUMP ? dump(db) : null;
        const before = {
            statuses: statusDistribution(db),
            outsidePending: activeTagsOutsidePending(db),
            markerRows: markerCount(db, map.marker),
            queue: new Map(),
        };
        const rows = candidates(db);
        const todo = [];
        const skipped = [];
        for (const r of rows) {
            const m = map.codeOf(r.result, r.downgrade);
            if (!m.known) { skipped.push({ ...r, why: `映射表里没有 (${r.result}, ${r.downgrade || '—'})` }); continue; }
            if (m.code == null) { skipped.push({ ...r, why: `${r.result} 这一类不落标（映射表 code=null）` }); continue; }
            let code = m.code;
            let note = '';
            if (r.result === 'FROZEN') {
                // 细分码（FROZEN_RETURN_APPLY…）由策略 action_json 决定，映射只给通用码。
                // 先用该单身上已有的 FROZEN% 码，找不到才用通用码 —— 不静默降级，输出里标出来。
                const family = r.allTagCodes.find((c) => c.startsWith(map.frozenPrefix));
                if (family && family !== code) { note = `冻结细分码取该单已有的 ${family}`; code = family; }
                else if (!family) note = '细分码未定 → 落冻结族通用码 FROZEN';
            }
            todo.push({ ...r, code, note });
        }

        // 按标筛现状（有后端的库才查；没有就明说跳过，不假装通过）
        const base = API_MAP[db];
        if (base && todo.length > 0) {
            try {
                const tk = await login(base);
                for (const code of [...new Set(todo.map((t) => t.code))]) {
                    const d = await apiFetch(base, `/admin/order-audit/queue?tag=${encodeURIComponent(code)}&page=1&size=1`, tk);
                    before.queue.set(code, num(d.total));
                }
            } catch (e) {
                console.log(`   ⚠ ${db} 的按标筛基线取不到（${base}）：${e.message}`);
            }
        }
        plans.push({ db, dumped, before, rows, todo, skipped });

        console.log(`\n   ▸ ${db}${dumped ? `   [dump ${(dumped.bytes / 1024).toFixed(0)} KB → ${dumped.file}]` : ''}`);
        console.log(`     候选（status=7 + 有决策行 + 无 active 结论标）：${rows.length} 条 → 将补 ${todo.length} 条，跳过 ${skipped.length} 条`);
        if (skipped.length > 0) {
            const byWhy = new Map();
            for (const s of skipped) byWhy.set(s.why, (byWhy.get(s.why) ?? 0) + 1);
            for (const [why, cnt] of byWhy) console.log(`     · 跳过 ${cnt} 条：${why}`);
        }
        todo.slice(0, SAMPLE).forEach((t, i) => {
            console.log(`     ${i + 1}. ${pad(t.orderSn, 18)} order=${t.orderId} decision=${t.decisionId} run=${t.runId} `
                + `result=${t.result}${t.downgrade ? `/${t.downgrade}` : ''} → ${t.code}${t.note ? `（${t.note}）` : ''}`);
        });
    }
    const totalTodo = plans.reduce((a, p) => a + p.todo.length, 0);
    console.log(`\n   合计将补：${totalTodo} 条`);

    if (DRY) {
        console.log('\n── DRY-RUN 结束：一条都没写。要真跑：再加 --apply --yes ──');
        return 0;
    }

    // ③ 写库（逐条，NOT EXISTS 幂等）
    console.log('\n── ③ 写库（逐条 INSERT，同单同码 active 已存在则不插）──');
    for (const p of plans) {
        let wrote = 0;
        for (const t of p.todo) {
            const reason = `补标：依据 run_id=${t.runId}/decision_id=${t.decisionId}；`
                + (t.reasonText || '（决策行无判定理由原文）');
            const clipped = reason.length <= 255 ? reason : `${reason.slice(0, 254)}…`;
            const beforeCnt = markerCount(p.db, map.marker);
            sql(p.db, `INSERT INTO order_audit_tag (order_id, tag_code, active, reason, released_by, created_at)
SELECT ${t.orderId}, ${q(t.code)}, 1, ${q(clipped)}, ${q(map.marker)}, NOW(3)
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM order_audit_tag x
                  WHERE x.order_id = ${t.orderId} AND x.tag_code = ${q(t.code)} AND x.active = 1);`);
            if (markerCount(p.db, map.marker) > beforeCnt) wrote++;
        }
        p.wrote = wrote;
        console.log(`   ▸ ${p.db}：写入 ${wrote} 条（计划 ${p.todo.length} 条）`);
    }

    // ④ 自检
    console.log('\n── ④ 自检 ──');
    let failed = 0;
    const ok = (m) => console.log(`   ✓ ${m}`);
    const bad = (m) => { failed++; console.log(`   ✗ ${m}`); };
    let grandInserted = 0;

    for (const p of plans) {
        const after = {
            statuses: statusDistribution(p.db),
            outsidePending: activeTagsOutsidePending(p.db),
            markerRows: markerCount(p.db, map.marker),
        };
        const inserted = after.markerRows - p.before.markerRows;
        grandInserted += inserted;
        const remaining = candidates(p.db);

        console.log(`\n   ▸ ${p.db}`);
        // 残留候选只允许是「本来就不落标」的那些（AUTO_PASS / SKIPPED / 映射缺失）；
        // 任何一条"该落标却没落"的残留都是失败 —— 这条判据比"残留必须为 0"更准：
        // AUTO_PASS 的单永远不会有结论标（通过由状态表达），把它算成失败会让自检永远红。
        const shouldBeFilled = remaining.filter((r) => {
            const m = map.codeOf(r.result, r.downgrade);
            return m.known && m.code != null;
        });
        console.log(`     ① 补标行数（released_by='${map.marker}' 的增量）：${inserted}`);
        console.log(`     ② 剩余未补：${remaining.length} 条（其中"本来就不落标" ${remaining.length - shouldBeFilled.length} 条，`
            + `"该落却没落" ${shouldBeFilled.length} 条）`);
        if (shouldBeFilled.length === 0) {
            ok(`${p.db}: 该补的都补上了；剩下的都是本来就不落标的结论（AUTO_PASS/SKIPPED/映射缺失）`);
        } else {
            shouldBeFilled.forEach((r) => bad(`${p.db}: 该落却没落 —— ${r.orderSn} order=${r.orderId} result=${r.result}`));
        }
        if (inserted === p.todo.length) ok(`${p.db}: 计划数 == 实际新增数（${inserted} == ${p.todo.length}）`);
        else bad(`${p.db}: 计划 ${p.todo.length} 条、实际新增 ${inserted} 条，不相等`);

        console.log(`     ③ status 分布 前：${p.before.statuses}`);
        console.log(`        status 分布 后：${after.statuses}`);
        if (p.before.statuses === after.statuses) ok(`${p.db}: status 分布前后一致（订单状态一个字没动）`);
        else bad(`${p.db}: status 分布变了 —— 补标不许碰订单状态`);

        console.log(`     ④ 已离开待审核单上的 active 标 前/后：${p.before.outsidePending} / ${after.outsidePending}`);
        if (p.before.outsidePending === after.outsidePending) ok(`${p.db}: 已离开待审核的单一条都没补`);
        else bad(`${p.db}: 已离开待审核的单被动了标（T3 的边界被破坏）`);

        // 反查：每条补的标都要能回到 decision_id，且码与那条判定的 result 对得上
        const traceErrors = [];
        const traced = [];
        for (const row of sql(p.db, `SELECT order_id, tag_code, reason FROM order_audit_tag
                                     WHERE released_by = ${q(map.marker)} AND active = 1 ORDER BY id`)) {
            const reasonText = str(row[2]);
            const m = /decision_id=(\d+)/.exec(reasonText);
            if (!m) { traceErrors.push(`order=${str(row[0])} ${str(row[1])}：reason 里没有 decision_id`); continue; }
            const decisionId = num(m[1]);
            const d = sql(p.db, `SELECT result, COALESCE(downgrade_reason,'') FROM order_audit_decision WHERE id = ${decisionId}`)[0];
            if (!d) { traceErrors.push(`order=${str(row[0])}：decision_id=${decisionId} 不存在`); continue; }
            const expect = map.codeOf(str(d[0]), str(d[1]));
            if (expect.code !== str(row[1])) {
                traceErrors.push(`order=${str(row[0])} ${str(row[1])}：decision=${decisionId} result=${str(d[0])} 应落 ${expect.code}`);
                continue;
            }
            traced.push({ orderId: str(row[0]), code: str(row[1]), decisionId, reason: reasonText });
        }
        if (traceErrors.length === 0) ok(`${p.db}: 每条补的标都能反查到 decision_id 且码与 result 对得上（${traced.length} 条）`);
        else traceErrors.forEach((e) => bad(`反向追溯失败：${e}`));
        traced.slice(0, 3).forEach((s) => {
            console.log(`     · order=${s.orderId} ${s.code} ← decision_id=${s.decisionId}`);
            console.log(`       reason=${cut(s.reason, 130)}`);
        });

        // ⑤ 按标筛增量 == 补标行数；⑥ 对账端点 pendingTagMissing
        const base = API_MAP[p.db];
        if (!base) {
            console.log(`     · 跳过「对账端点 / 按标筛增量」：--api-map 里没有 ${p.db} 的后端（不假装通过）`);
            continue;
        }
        try {
            const tk = await login(base);
            const parity = await apiFetch(base, '/admin/order-audit/queue/conclusion-parity?limit=5', tk);
            console.log(`     ⑤ 对账端点 pendingTagMissing：${parity.pendingTagMissing}（scanned=${parity.scanned}，队列行）`);
            if (num(parity.pendingTagMissing) === 0) ok(`${p.db}: pendingTagMissing == 0`);
            else bad(`${p.db}: pendingTagMissing = ${parity.pendingTagMissing}（补完应为 0）`);

            for (const code of [...new Set(p.todo.map((t) => t.code))]) {
                const now = num((await apiFetch(base, `/admin/order-audit/queue?tag=${encodeURIComponent(code)}&page=1&size=1`, tk)).total);
                const base0 = p.before.queue.get(code);
                const mine = p.todo.filter((t) => t.code === code).length;   // 本库该类补标行数
                if (base0 === undefined) {
                    console.log(`     ⑥ 按标筛 ?tag=${code}：${now} 条（基线未取到，跳过增量比对）`);
                } else {
                    const delta = now - base0;
                    console.log(`     ⑥ 按标筛 ?tag=${code}：${base0} → ${now} 条，增量 ${delta}；本库该类补标 ${mine} 条`);
                    if (delta === mine) ok(`${p.db}: 「${code}」筛选增量 ${delta} == 补标行数 ${mine}`);
                    else bad(`${p.db}: 「${code}」筛选增量 ${delta} != 补标行数 ${mine}`);
                }
            }
        } catch (e) {
            bad(`${p.db}: 调后端自检失败（${base}）：${e.message}`);
        }
    }

    console.log(`\n补标行数合计：${grandInserted}`);
    console.log(failed === 0 ? '\n✅ 自检全绿' : `\n❌ 自检有 ${failed} 项失败`);
    return failed === 0 ? 0 : 1;
}

main().then((code) => process.exit(code)).catch((e) => {
    console.error(`✗ ${e.message}`);
    process.exit(1);
});
