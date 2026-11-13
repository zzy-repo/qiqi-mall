import { test } from '../../support/test.js'
import { API, stamp } from '../../support/env.js'
import { queryDb } from '../../support/db.js'
import { adminLoginHardened } from '../../support/admin-ui.js'
import { adminTokenOf } from '../../support/assert.js'

// 写实（去掉 fixme）时再补这些：
//   expect / tid / tidPrefix                 —— support/test.js、support/tid.js（页面断言）
//   memberRegisterAndLogin / addSpecToCart / submitCartOrder —— support/ui.js（买家侧支付）
//   memberNickOf / receiverOf                —— support/fixtures.js
//   memberLoginOf                            —— support/member-login.js
//   memberApplyReturn / auditReturnApply     —— support/admin-ui-return.js（触发退款）

/**
 * 03-商家-履约 / 15-支付流水加固（骨架，未写实）
 *
 * 这个场景要证明的一件事：
 *   **钱进来的每一次、出去的每一次都能对上订单，也能对上前一笔 ——
 *     而且同一笔钱不会被记两次（渠道流水号 + 本地幂等键两道闸）。**
 *
 * 已经能确定的事实（payment_record 原地加固，不新建表）：
 *   · 每条流水记清：关联订单（订单号 + 订单ID）、金额、类型（支付 / 退款）、渠道、渠道流水号；
 *   · 退款那条必须带 origin_flow_id，指向它要退的那一笔支付流水；
 *   · ref_type / ref_id 记「触发它的单据」（例如退货申请），operator_* 记是谁做的；
 *   · 两道闸各管一件事：
 *       ① uk_channel_trade（渠道 + 渠道流水号）唯一 —— 挡住第三方重复通知；
 *       ② uk_idem（本地幂等键）唯一 —— 挡住自己连点与重试；
 *   · 订单被物理删除之后，流水里的 order_sn / order_id 仍然读得到（对账键不随订单消失）。
 *
 * ── 缺什么才能写实（四条，第一条是硬缺口）────────────────────────────────
 *   ① **支付流水没有 UI 入口**：台账页的六个类型里没有「支付」（只有订单状态 / 包裹状态 /
 *      售后状态 / 审计 / WMS 回调 / 库存），也没有独立的流水页。按套件口径「UI 触发 + 页面断言」，
 *      现在只能直查库 —— 那就不是场景验证。要写实，必须先定其中一条：
 *        · 给台账页加一个「支付」类型（后端补一个分支），或
 *        · 另开一页支付流水查询（列表 + 详情两张 testid）。两者都要先定 bizId 的过滤口径。
 *   ② 「第三方重复通知」造不出来：现在只有「模拟支付」，渠道流水号是本地生成的，
 *      没有可重放的渠道回调。要验「同一笔钱只入账一次」，得先有一个能带 trade_no 重放的
 *      支付回调入口（或测试用的模拟回调），否则第一道闸只能靠书面论证。
 *   ③ 退款流水的 origin_flow_id 只在「退货审核通过」时写。写实前要确认
 *      `qiqi.ledger.payment-trace-enabled` 在 e2e 环境是开的（默认开，但环境配错了会静默不写）。
 *   ④ 历史 14 行流水的 idem_key 全是 NULL（回填只做了 order_id）。
 *      所以「新流水必须有幂等键」这类断言必须按 created_at / id 收窄到本用例新造的那几条，
 *      不能对整表断言 —— 否则一上手就是假红。
 *
 * ── 另外（写实前先确认）──────────────────────────────────────────────────
 *   · type=2 的退款流水「必填 origin_flow_id」目前只是列注释里的口径，库上没有 CHECK。
 *     要把它变成可断言的规则，得先决定是加约束还是在服务层守卫（两者对用例写法不同）。
 */

const dbCount = (sql) => Number(queryDb(sql))

/** 该订单的支付流水（type=1）—— 「这笔单付过多少钱」 */
const payFlowOf = (orderSn) => queryDb(
  `select id from payment_record where order_sn = '${orderSn}' and type = 1 order by id limit 1`
)

/** 该订单的退款流水（type=2） */
const refundFlowsOf = (orderSn) => queryDb(
  `select group_concat(id order by id separator ',') from payment_record
    where order_sn = '${orderSn}' and type = 2`
).split(',').filter(Boolean).map(Number)

/** 本用例新造的那几条流水（按 id 收窄，避开历史 14 行 idem_key 为 NULL 的老数据） */
const flowsSince = (fromId, orderSn) =>
  dbCount(`select count(*) from payment_record where id > ${fromId} and order_sn = '${orderSn}'`)

/** 支付流水表当前最大 id：每段用例开始前记一下，用来只断言「自己造的那几条」 */
const paymentMaxId = () => dbCount('select coalesce(max(id), 0) from payment_record')

test.describe('03-商家-履约 › 15-支付流水加固', () => {
  test('用例一（骨架）：付一条、退一条 —— 每条都带订单、触发单据、操作人与幂等键', async ({ page }) => {
    test.fixme(true, '支付流水没有 UI 入口（台账页无「支付」类型）：页面断言写不出来，只有库断言的用例不算场景验证')

    // ── 骨架（写实时要按这个顺序补满）────────────────────────────────────
    // ① 新买家注册登录 → 加购 → 结算支付（走模拟支付）→ 记 fromId = paymentMaxId()
    // ② 断言库（只看本用例新造的行）：1 条 type=1，金额＝订单总额，
    //    order_sn 与 order_id 都对得上，idem_key 非空，渠道流水号非空
    // ③ 后台审核通过 → 下发 → 出库 → 买家申请退货 → 后台审核通过（触发退款）
    // ④ 断言库：新增 1 条 type=2，金额＝退款额，origin_flow_id 等于第②步那条支付流水的 id
    //    （"退的是哪一笔钱"靠这一列回答，没有它只能拿金额和时间猜）
    // ⑤ 断言库：退款那条的 ref_type / ref_id 指向那张退货申请（触发单据落得住）
    // ⑥ 断言库：同一条流水不能被记两次 —— 对同一触发重放一次，行数不变（本地幂等键挡住）
    // ⑦ 页面：写实后应能在（新增的）支付类型页面上筛出这两行，金额与类型看得见
    let orderSn = null
    let token = null
    try {
      await adminLoginHardened(page)
      token = await adminTokenOf(page)
      orderSn = `骨架占位-${stamp()}`
    } finally {
      // 清理：买家账号 + 订单（订单删除会带走流水，那是产品口径；这里只保证不留自造数据）
      // 写实时要在这里调用 cleanupSelfMadeOrder + cleanupSelfMadeMember（照 8-订单删除守卫 的写法）
    }
  })

  test('用例二（骨架）：渠道重复通知不会变成两笔钱 —— 渠道流水号唯一是最后一道闸', async ({ page }) => {
    test.fixme(true, '没有可重放的渠道回调（模拟支付不产生可复用的第三方流水号），造不出「重复通知」')

    // ── 骨架（写实时要按这个顺序补满）────────────────────────────────────
    // ① 造一笔支付（或直接用测试用的支付回调入口，带固定的渠道流水号 trade_no）
    // ② 断言库：1 条流水
    // ③ 用**同一个** trade_no 再通知一次（同渠道）
    // ④ 断言库：仍 1 条 —— 撞 uk_channel_trade，第二次被唯一键挡在外面
    //    （断言要落在「行数没变 + 金额总和没变」，只看接口返回码不够：
    //      失败也可能被吞掉后照样返回成功）
    // ⑤ 反向对照：换一个 trade_no 再通知，应当**成功**多一条（证明不是把正常通知也挡了）
    let orderSn = null
    try {
      await adminLoginHardened(page)
      orderSn = `骨架占位-${stamp()}`
    } finally {
      // 清理：删单 + 删本用例自造的买家账号（写实时按 8-订单删除守卫 的顺序断依据再删单）
    }
  })

  test('用例三（骨架）：订单被物理删除后，流水里的订单号仍读得到 —— 对账不会凭空少一笔', async ({ page, request }) => {
    test.fixme(true, '缺「删单后按订单号反查流水」的 UI 入口（台账页无支付类型），只能直查库')

    // ── 骨架（写实时要按这个顺序补满）────────────────────────────────────
    // ① 造单支付（产生 1 条支付流水）→ 记下订单号、流水 id、金额
    // ② 断掉删单守卫的依据，真删这张单
    // ③ 断言库：order 0 行；但 payment_record 里那条流水的 order_sn 仍等于原单号
    //    （order_id 允许悬空 —— 列注释里写明「order 被物理删后可能悬空」，这是设计）
    // ④ 断言库：按订单号汇总的金额与删单前一致（对账不会少）
    // ⑤ 页面：写实后应能按订单号筛出这条流水
    let orderSn = null
    let token = null
    try {
      await adminLoginHardened(page)
      token = await adminTokenOf(page)
      orderSn = `骨架占位-${stamp()}`
    } finally {
      // 清理：删单 + 删本用例自造的买家账号（写实时按 8-订单删除守卫 的顺序断依据再删单）
    }
  })
})
