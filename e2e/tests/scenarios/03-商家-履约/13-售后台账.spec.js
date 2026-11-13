import { test } from '../../support/test.js'
import { API, stamp } from '../../support/env.js'
import { queryDb } from '../../support/db.js'
import { memberNickOf, receiverOf } from '../../support/fixtures.js'
import { memberLoginOf } from '../../support/member-login.js'
import { adminLoginHardened } from '../../support/admin-ui.js'
import { adminTokenOf } from '../../support/assert.js'

// 写实（去掉 fixme）时再补这些：
//   expect                        —— support/test.js
//   tid / tidPrefix               —— support/tid.js（台账页行锚点）
//   memberRegisterAndLogin / addSpecToCart / submitCartOrder —— support/ui.js（买家侧下单）
//   gotoOrderList / searchOrderRow / auditPassOrder / expectToastSuccess —— support/admin-ui.js
//   memberApplyReturn / auditReturnApply                     —— support/admin-ui-return.js（申请与审核）
//   getProductApi / getAdminOrderBySnApi                     —— support/assert.js
//   returnDescriptionOf                                      —— support/fixtures.js

/**
 * 03-商家-履约 / 13-售后台账（骨架，未写实）
 *
 * 这个场景要证明的一件事：
 *   **一张退货申请走完全程也只留几行台账，每行都带着订单号 ——
 *     所以订单被物理删除、申请行跟着级联消失之后，售后流水仍然能按订单查回来。**
 *
 * 已经能确定的事实：
 *   · 台账表：return_status_ledger，业务主键是退货申请ID，另带一列 order_id（已登记的冗余列）；
 *   · 一行 = 一次流转：前态 / 后态 / 原因码（RETURN_CREATE / RETURN_APPROVE / RETURN_REJECT）/
 *     操作人（MEMBER 提交、ADMIN 审核）/ 触发单据 / 幂等键 = 申请ID:流转序号；
 *   · 申请状态只有三档：待处理 / 已通过 / 已拒绝，所以「同一笔申请只审一次」在台账里
 *     必然表现为一行通过或一行拒绝，不会两条都出现。
 *
 * ── 缺什么才能写实（四条）────────────────────────────────────────────────
 *   ① 后端 `GET /admin/ledgers` 的售后分支还没进仓（台账页前端已就绪）。
 *   ② `bizId` 的过滤口径未定：售后台账既可以按「退货申请ID」也可以按「订单ID」查，
 *      而台账页只有一个数字输入框（ledger-biz-id-input）。必须先把口径定成其中一个
 *      （建议：bizId = 申请ID，另给一个 orderId 过滤），否则用例写出来就是在猜契约。
 *   ③ 「删单之后仍能按订单反查」需要删单，但**当前 admin 端允许同一订单留下两条待处理申请**
 *      （sql/migrate-ledger.sql 头部【已知问题·待办】：唯一键刻意没建）——
 *      这会让「删单前只有一条申请」变成不确定的前提。要么先补那道守卫 + 唯一键，
 *      要么在用例里显式构造并接受「可能两条」，两种口径不能混着写。
 *   ④ 退款流水那一行的 origin_flow_id（退款指向原支付流水）属于 15-支付流水加固 的口径，
 *      跨场景；写实前先确认支付侧加固上线，否则这一段的断言只能留给 15。
 *
 * ── 另外（写实前先确认）──────────────────────────────────────────────────
 *   · 拒绝路径会把订单从「退款中」交回原状态（直发单回已付款、包裹都没出库时回待发货），
 *     所以拒绝用例的订单状态断言要按「包裹是否出库」分两种，不能一律写死。
 */

const dbCount = (sql) => Number(queryDb(sql))

/** 该订单的退货申请（本场景只处理第一个） */
const returnApplyIdOf = (orderId) => Number(queryDb(
  `select id from return_apply where order_id = ${orderId} order by id limit 1`
))

/** 该申请在售后台账里的行数 */
const ledgerRowsOf = (returnApplyId) =>
  dbCount(`select count(*) from return_status_ledger where return_apply_id = ${returnApplyId}`)

/** 原因码序列 */
const reasonsOf = (returnApplyId) => queryDb(
  `select coalesce(group_concat(reason order by id separator '、'), '')
     from return_status_ledger where return_apply_id = ${returnApplyId}`
)

/** 订单号冗余列序列（每一行都该带着它） */
const orderIdSnapshotsOf = (returnApplyId) => queryDb(
  `select coalesce(group_concat(coalesce(order_id, 0) order by id separator '、'), '')
     from return_status_ledger where return_apply_id = ${returnApplyId}`
)

async function cleanupSelfMadeOrder(request, { orderId, orderSn, token }) {
  const id = orderId
    || (orderSn ? Number(queryDb(`select id from \`order\` where order_sn = '${orderSn}'`) || 0) : 0)
  if (!id) return
  if (dbCount(`select count(*) from \`order\` where id = ${id}`) === 0) return
  try {
    queryDb(`delete from delivery_logistics where delivery_id in
               (select id from delivery_order where order_id = ${id})`)
    queryDb(`delete from return_apply where order_id = ${id}`)
    queryDb(`update delivery_order set status = 0 where order_id = ${id} and status <> 9`)
    const res = await request.delete(`${API}/admin/orders/${id}`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    const body = await res.json()
    if (body.code !== 0) console.warn(`[13-售后台账] 清理未完成（orderId=${id}）：${body.message}`)
  } catch (e) {
    console.warn(`[13-售后台账] 清理抛错（orderId=${id}）：${e.message}`)
  }
}

test.describe('03-商家-履约 › 13-售后台账', () => {
  test('用例一（骨架）：提交申请留出生行（带订单号），审核通过再留一行', async ({ page, request }) => {
    test.fixme(true, '缺 GET /admin/ledgers 售后分支 + bizId 过滤口径（申请ID 还是订单ID 未定）')

    // ── 骨架（写实时要按这个顺序补满）────────────────────────────────────
    // ① 新买家注册登录 → 加购 → 结算支付（订单进「待审核」）
    // ② 后台审核通过 → 下发 → 模拟仓库回传出库（订单变已发货，才允许申请退货）
    // ③ 买家在「我的订单」申请退货 → 断言库：售后台账 1 行，前态 NULL、后态待处理、
    //    原因 RETURN_CREATE、操作人类型 MEMBER、order_id 冗余已填
    // ④ 后台「退货申请」页审核通过 → 断言库 2 行，原因序列 RETURN_CREATE、RETURN_APPROVE，
    //    后态 已通过，操作人类型 ADMIN；订单变「已退款」
    // ⑤ 断言每一行的 order_id 冗余都等于这张单的 id（删单后靠它反查）
    // ⑥ 断言幂等键 = 申请ID:流转序号，序号互不相同
    // ⑦ 台账页：售后类型 + 业务ID → 与库里行数一致，页面能看到「待处理 → 已通过」
    // ⑧ finally：清理买家账号 + 订单（订单删掉时申请会级联删除，但台账行必须留着）
    let orderSn = null
    let orderId = null
    let token = null
    let username = null
    try {
      await adminLoginHardened(page)
      token = await adminTokenOf(page)
      username = memberLoginOf(stamp())
      await memberRegisterAndLogin(page, username, '123456', memberNickOf(username))
      orderSn = `骨架占位-${stamp()}`   // 写实时用 submitCartOrder 拿真实单号
      orderId = null
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })

  test('用例二（骨架）：审核拒绝是另一条原因，申请落「已拒绝」，订单交回原状态', async ({ page, request }) => {
    test.fixme(true, '缺 GET /admin/ledgers 售后分支；且「拒绝后订单回哪个状态」要按包裹是否出库分两种口径')

    // ── 骨架（写实时要按这个顺序补满）────────────────────────────────────
    // ① 买家下单支付 → 后台审核通过 → 出库 → 买家申请退货
    // ② 后台审核「拒绝」→ 断言库：2 行，原因序列 RETURN_CREATE、RETURN_REJECT，后态 已拒绝
    // ③ 断言订单状态按口径回退：直发单回「已付款」；包裹都没出库时回「待发货」
    //    （同一个入口两种落点，写死一种就会在另一种场景假红）
    // ④ 断言库存没被回补（拒绝 ≠ 退款）：该单在库存台账里的净额仍是「只扣未补」
    // ⑤ 台账页：售后类型 + 业务ID → 两行，页面能看到「已拒绝」
    let orderSn = null
    let orderId = null
    let token = null
    try {
      await adminLoginHardened(page)
      token = await adminTokenOf(page)
      orderSn = `骨架占位-${stamp()}`
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })

  test('用例三（骨架）：订单被物理删除后，售后台账仍能按订单号反查 —— 这正是 order_id 冗余的理由', async ({ page, request }) => {
    test.fixme(true, '缺 GET /admin/ledgers 售后分支（订单ID过滤）；删单链路已具备')

    // ── 骨架（写实时要按这个顺序补满）────────────────────────────────────
    // ① 买家下单支付 → 后台审核通过 → 出库 → 买家申请退货（申请待处理）
    // ② 记下：申请ID、订单ID、台账行数与原因码序列
    // ③ 断掉删单守卫的依据（物流行 / 退货申请 / 已出库包裹），真删这张单
    // ④ 断言库：order 0 行、return_apply 0 行（申请随订单级联删除）
    // ⑤ 关键断言：return_status_ledger 里这个申请的行一条不少，且每行的 order_id 冗余
    //    仍等于第②步记下的订单ID —— 没有这一列，「售后流水」就彻底断链了
    // ⑥ （写实后）台账页应能按订单ID查到这些行，且「业务单号」列不是「—」
    let orderSn = null
    let orderId = null
    let token = null
    try {
      await adminLoginHardened(page)
      token = await adminTokenOf(page)
      orderSn = `骨架占位-${stamp()}`
    } finally {
      await cleanupSelfMadeOrder(request, { orderId, orderSn, token })
    }
  })
})
