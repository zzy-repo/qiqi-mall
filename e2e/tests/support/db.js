/**
 * 「端口 → 数据库名」的唯一事实来源 + 直连 mysql 的只读查询通道。
 *
 * 为什么要有这个文件：这套映射以前在 3 个 spec 里各写了一遍（08 支付并发 / 09 确认收货并发 /
 * 10 同商品多人抢购），已经因此出过三次故障，全部是「测试层自己算环境事实、算错了」：
 *   ① fixtures 名字撞名；② e2e 日志文件名撞名；③ 08 那份 DB_OF_PORT 少一行 18080，
 *   录 demo（容器栈，API_BASE 固定 18080）时直接抛「认不出 API_BASE 对应哪个分片库」。
 * 所以映射只许写在这里一份，spec 一律 import，不许再本地定义一个 `DB_OF_PORT`。
 *
 * 依赖边界：只依赖 node 内置模块 + env.js（env.js 是最底层，谁也不依赖）→ 不会引入循环依赖。
 *
 * 凭据读取顺序（两套变量名都必须能跑，别只认一套）：
 *   先 MYSQL_HOST/MYSQL_PORT/MYSQL_USER/MYSQL_PASSWORD（3 个并发 spec 用的这套），
 *   读不到再回落 DB_HOST/DB_PORT/DB_USER/DB_PASSWORD（admin-ui-return.js 用的那套，
 *   历史上它写的是 DB_PASS，这里也一并认）。
 */
import { execFileSync } from 'node:child_process'
import { API } from './env.js'

/**
 * 端口 → 数据库名：e2e 分片库（8091~8094）+ 容器栈测试库（18080）+ 本机 dev（8081）。
 *
 *  8081  qiqi_mall         本机 dev 后端（scripts/dev*.sh）
 *  8091  qiqi_mall_e2e_a   e2e shard a（scripts/e2e-run.sh 的 SHARDS 表）
 *  8092  qiqi_mall_e2e_b   e2e shard b
 *  8093  qiqi_mall_e2e_c   e2e shard c
 *  8094  qiqi_mall_e2e_d   e2e shard d
 *  18080 qiqi_mall_test    容器栈（docker compose，demo 录制 scripts/demo-run.sh）
 *
 * 新环境（新端口 / 新库）就在这里补一行 —— 补完 3 个 spec 自动跟上，这是本文件存在的意义。
 */
export const DB_OF_PORT = {
  8081: 'qiqi_mall', // 本机 dev 后端
  8091: 'qiqi_mall_e2e_a', // e2e shard a
  8092: 'qiqi_mall_e2e_b',
  8093: 'qiqi_mall_e2e_c',
  8094: 'qiqi_mall_e2e_d',
  18080: 'qiqi_mall_test', // 容器栈（demo 录制）
}

/** mysql 客户端连接参数（MYSQL_* 优先，回落 DB_*；都读不到才用默认值） */
const conn = () => ({
  host: process.env.MYSQL_HOST || process.env.DB_HOST || '127.0.0.1',
  port: process.env.MYSQL_PORT || process.env.DB_PORT || '3306',
  user: process.env.MYSQL_USER || process.env.DB_USER || 'root',
  pass: process.env.MYSQL_PASSWORD || process.env.DB_PASSWORD || process.env.DB_PASS || 'qiqi123456',
})

/** API_BASE 的端口；解析不出端口时给 0（落到 dbOfApi 的统一报错里，不在这里抛） */
const portOf = (apiBase) => {
  try {
    const url = new URL(apiBase)
    return Number(url.port || (url.protocol === 'https:' ? 443 : 80))
  } catch {
    return 0
  }
}

/** 「已知映射」提示行：由 DB_OF_PORT 现算，永远与上表同步，不会写死导致漂移 */
const knownMapping = () =>
  Object.entries(DB_OF_PORT)
    .map(([port, db]) => `${port}→${db}`)
    .join(', ')

/**
 * 当前这套环境连的是哪个库：端口 → 库名。
 * 推不出来就抛**带排查提示**的中文错误（不要只抛「undefined」让下一个人猜半小时）。
 */
export function dbOfApi() {
  const port = portOf(API)
  const db = DB_OF_PORT[port]
  if (!db) {
    throw new Error(
      `认不出 API_BASE=${API} 对应哪个库。\n`
      + `已知映射：${knownMapping()}。\n`
      + '如果你在跑新的环境，请在 e2e/tests/support/db.js 的 DB_OF_PORT 里补一行。'
    )
  }
  return db
}

/** 底层：调 mysql 客户端，返回原始 TSV 文本（-N 无表头 -B 制表符分隔），已 trim */
function mysqlRaw(sql, dbName) {
  const { host, port, user, pass } = conn()
  return execFileSync(
    'mysql',
    ['-h', host, '-P', port, `-u${user}`, `-p${pass}`, '-N', '-B', '-e', sql, dbName],
    { encoding: 'utf8', timeout: 20_000 }
  ).trim()
}

/**
 * 查**当前**库（库名由 API_BASE 决定，见 dbOfApi）。
 * 返回原始文本：单值查询直接 Number(...)，多列查询自己 split('\t')。写操作只允许清理自造数据。
 */
export function queryDb(sql) {
  return mysqlRaw(sql, dbOfApi())
}

/**
 * 执行一段**可多语句**的 SQL（建数据 / 清数据用）。
 *
 * 只给「本用例自己造的事实」开这个口子，两条硬约束：
 *  1) **不许**用它推进业务状态（审核/发货/退款一律走 UI 点击，那才是被测对象）；
 *  2) 只允许两种用途：① 铺**种子基线**（例如跑 sql/seed-order-audit-demo.sql）；
 *     ② 清理/复位本用例碰过的行（决策、标、台账、把订单还原成待审核）。
 * 需要第三种用途时先想清楚：能不能走接口或 UI —— 能，就别写在这里。
 *
 * @param sql 可以含多条语句（mysql -e 支持），执行失败会抛带 mysql 原文的错误
 * @param dbName 目标库；不传 = 当前 API_BASE 推导出的库（见 dbOfApi）
 */
export function execSql(sql, dbName) {
  return mysqlRaw(sql, dbName || dbOfApi())
}

/**
 * 查**指定**库（供 admin-ui-return.js 这类「一个查询要试几个候选库」的场景复用）。
 * 返回 行 × 列 的二维数组（与 admin-ui-return.js 原本的 mysqlQuery 同形）。
 */
export function mysqlQuery(sql, dbName) {
  return mysqlRaw(sql, dbName)
    .split('\n')
    .filter(Boolean)
    .map((line) => line.split('\t'))
}

/**
 * 候选库列表：一个查询不知道该查哪个库时，按这个顺序试（命中即返回）。
 *
 * 顺序：显式 DB_NAME 优先 → e2e 独立库（a/b）→ 老的 dev 库 qiqi_mall → 容器栈测试库 qiqi_mall_test。
 * 注意不能「全部查一遍再合并」：订单只存在于其中一个库，查错的库永远 0 行，
 * 全量跑时会表现为「退款流水应恰好有一条，实到 0」。
 */
export function dbCandidates() {
  return [
    process.env.DB_NAME,
    'qiqi_mall_e2e_a',
    'qiqi_mall_e2e_b',
    'qiqi_mall',
    'qiqi_mall_test',
  ].filter(Boolean)
}
