/**
 * 台账页「重放渠道通知」的三态回归门禁（toast 级别 + 弹窗去向 + 行数不变）。
 *
 * ── 这个文件为什么存在 ───────────────────────────────────────────────────────
 * `views/ledger/index.vue` 的 replay() 按后端 `data.outcome` 分三态提示：
 *   · outcome=RECORDED  → success「渠道通知已入账」（真的记了一笔）
 *   · outcome=DUPLICATE → **warning**「重复通知，已忽略（流水未增加）」  ← 不是错误！
 *                          这是渠道的正常重复投递，行数不变才是正确结果
 *   · code=1（沙箱开关关着 / 流水不存在 / 没 trade_no / 疑似重复扣款要人工对账）
 *                       → error 弹后端中文原文，并且**弹窗不关**（原因留在弹窗里）
 *
 * 曾经踩过的坑（2026-11-14）：DUPLICATE 在 exec 里被转成带 `duplicateNotice` 标记的异常，
 * 交给 onError 认领后换成 warning 级提示。而 `useDangerConfirm` 的判定写的是
 * `_onError(e) === true`、**不 await** —— onError 一旦写成 `async` 就返回 Promise，
 * `Promise === true` 恒为 false ⇒ 接管静默失效 ⇒「重复通知，已忽略」掉进弹窗的红色错误区，
 * 界面上把「渠道又发了一遍通知，我们正确地没多记」显示成「执行失败」。语义正好做反。
 *
 * 原先只在 /tmp 有一份一次性 harness（stub element-plus 的 ElMessage，直接驱动真 useDangerConfirm，
 * 11 条断言）。/tmp 会被清理、也没法在 CI 里跑，所以这里把同一组断言搬到能长期跑的地方：
 * 不在进程内 stub ElMessage，而是**在同一套语义的 DOM 证据上断言** ——
 * ElMessage 的级别在页面上就是 `.el-message--success / --warning / --error`，
 * 「弹窗关不关」就是 `danger-confirm-dialog` 的可见性，「行数没变」就是 `ledger-row-*` 的计数。
 * 三者合起来与原来那 11 条一一对应（映射见文件末尾的对照表）。
 *
 * 定位器口径：`danger-confirm-*` 一律从 danger-ui.js 取，这里不重复定义。
 */
import { expect } from '@playwright/test'
import { tid, tidPrefix } from './tid.js'
import { dangerDialog, dangerError, dangerOk, waitDanger } from './danger-ui.js'

// ---------------------------------------------------------------------------
// 三态口径：级别 + 必须出现的文案
// ---------------------------------------------------------------------------

/**
 * outcome → 期望的 toast 级别与文案。
 *
 * 前两个来自后端 `ReplayOutcome` 枚举；第三个是「code=1 的失败态」在测试里的别名
 * （后端失败没有 outcome 字段，只有中文 message，所以用 `FAIL` 当别名，文案由用例传）。
 */
export const REPLAY_OUTCOME = {
  RECORDED: 'RECORDED',
  DUPLICATE: 'DUPLICATE',
  /** code=1 / HTTP 400：沙箱开关关着、流水不存在、没有 trade_no、疑似重复扣款需人工对账 */
  FAIL: 'FAIL'
}

/** 每种结局的「必须出现的 toast」：级别是这里钉死的，文案给默认值，用例可覆盖 */
export const REPLAY_TOAST = {
  RECORDED: {
    level: 'success',
    text: '渠道通知已入账',
    why: 'RECORDED = 真的新增了一条流水，只能是成功提示'
  },
  DUPLICATE: {
    level: 'warning',
    // 只锁前半句：括号里的「（流水未增加）」是给人看的补充，改措辞不该让门禁红
    text: '重复通知，已忽略',
    why: 'DUPLICATE 是渠道的正常重复投递，不是错误 —— 必须是 warning，绝不能是 error'
  },
  FAIL: {
    level: 'error',
    text: '',
    why: 'code=1 是失败，用 error 级弹后端中文原文'
  }
}

// ---------------------------------------------------------------------------
// DOM 定位（toast 没有模板锚点，只能按 Element Plus 的 class，见 tid.js 第 8 条）
// ---------------------------------------------------------------------------

/** 指定级别的 toast（Element Plus 类名 → 级别） */
export const toastOf = (page, level) => page.locator(`.el-message--${level}`)

/** 台账表的行锚点（`ledger-row-<type>-<id>`，前缀匹配即可枚举当前页所有行） */
export const ledgerRows = (page) => tidPrefix(page, 'ledger-row-')

/** 当前台账页的行数（点前点后要比的那个数） */
export const ledgerRowCount = (page) => ledgerRows(page).count()

/** 某一行的「重放渠道通知」按钮：rowKey = `${type}-${id}`（见 views/ledger/index.vue 的 rowKey） */
export const replayButton = (page, paymentId, type = 'PAYMENT') => tid(page, `ledger-replay-${type}-${paymentId}`)

// ---------------------------------------------------------------------------
// toast 录像机
//
// 为什么不能「点完再看 page.locator('.el-message--error')」：
//   ① ElMessage 默认 3 秒自动消失，慢一步就看不见了（判空的断言尤其危险：**没看到**不等于**没出现**）；
//   ② 要断言的是「**从来没有**出现过 error 级」，而不是「此刻没有」。
// 所以点之前先装一个 MutationObserver，把每一个出现过的 .el-message（级别 + 文案）记进
// `window.__replayToastLog`，之后再对着这份台账断言。页面不跳转（SPA 内点击），记录不会丢。
// ---------------------------------------------------------------------------

/** 装录像机（在点击「重放渠道通知」**之前**调用） */
export const installToastRecorder = async (page) => {
  await page.evaluate(() => {
    window.__replayToastLog = []
    const levelOf = (el) => {
      const hit = String(el.className || '').match(/el-message--(success|warning|error|info)/)
      return hit ? hit[1] : 'unknown'
    }
    const record = (el) => {
      if (el.__replayToastRecorded) return
      el.__replayToastRecorded = true
      window.__replayToastLog.push({ level: levelOf(el), text: (el.innerText || '').trim() })
    }
    const scan = (root) => {
      if (!root || !root.querySelectorAll) return
      if (root.classList && root.classList.contains('el-message')) record(root)
      root.querySelectorAll('.el-message').forEach(record)
    }
    scan(document.body)
    if (window.__replayToastObserver) window.__replayToastObserver.disconnect()
    const observer = new MutationObserver((mutations) => {
      for (const m of mutations) {
        for (const n of m.addedNodes) {
          if (n.nodeType === 1) scan(n)
        }
      }
    })
    observer.observe(document.body, { childList: true, subtree: true })
    window.__replayToastObserver = observer
  })
}

/** 读出目前录到的全部 toast：[{ level, text }]（按出现顺序） */
export const recordedToasts = (page) => page.evaluate(() => (window.__replayToastLog || []).slice())

/** 停掉录像机（用例末尾调用；不调也只是留一个 observer，页面关掉就没了） */
export const stopToastRecorder = async (page) => {
  await page.evaluate(() => {
    if (window.__replayToastObserver) window.__replayToastObserver.disconnect()
    window.__replayToastObserver = null
  })
}

// ---------------------------------------------------------------------------
// 断言小件
// ---------------------------------------------------------------------------

/** 等到「某个级别 + 文案包含」的 toast 被录到（用 expect.poll 轮询录像台账，不依赖 toast 还挂在页面上） */
const waitToast = async (page, level, text, timeout) => {
  await expect
    .poll(
      async () => (await recordedToasts(page)).some((t) => t.level === level && (!text || t.text.includes(text))),
      {
        timeout,
        message: `应出现 ${level} 级提示${text ? `（含「${text}」）` : ''}；`
          + `实际录到：${JSON.stringify(await recordedToasts(page))}`
      }
    )
    .toBe(true)
}

/** 断言「这一级别从来没有出现过」，失败时把录到的全部 toast 一起打出来（排查用） */
const expectNoLevel = async (page, level, message) => {
  const seen = await recordedToasts(page)
  const hits = seen.filter((t) => t.level === level)
  expect(hits, `${message}\n实际录到的全部提示：${JSON.stringify(seen)}`).toHaveLength(0)
}

// ---------------------------------------------------------------------------
// 主断言：expectReplayToast(page, outcome, opts)
// ---------------------------------------------------------------------------

/**
 * 断言一次「重放渠道通知」的结局符合三态口径。
 *
 * 调用姿势（点击之前先装录像机，点完再断言）：
 *
 *   await installToastRecorder(page)
 *   const rowsBefore = await ledgerRowCount(page)
 *   await replayButton(page, paymentId).click()
 *   await waitDanger(page, { willDo: '再走一遍渠道回调' })
 *   await dangerOk(page).click()
 *   await expectReplayToast(page, 'DUPLICATE', { rowsBefore })
 *
 * @param {'RECORDED'|'DUPLICATE'|'FAIL'} outcome 期望的结局
 * @param {object} [opts]
 * @param {string} [opts.text]          覆盖默认文案（FAIL 必传后端原文；其余可不传）
 * @param {number} [opts.rowsBefore]    点之前的台账行数：给了就在断言里比「点前点后不变」——
 *                                      DUPLICATE 的语义就是「行数不变」，这是它存在的意义
 * @param {number} [opts.timeout]       等 toast 的上限（默认 15s）
 * @param {number} [opts.settleMs]      toast 之后再多观察一会儿（默认 600ms），
 *                                      防「warning 先来、error 后到」这种半对的实现被判绿
 */
export const expectReplayToast = async (page, outcome, opts = {}) => {
  const { text: textOverride, rowsBefore, timeout = 15_000, settleMs = 600 } = opts
  const spec = REPLAY_TOAST[outcome]
  if (!spec) {
    throw new Error(`expectReplayToast：未知结局 ${outcome}（可选：${Object.keys(REPLAY_TOAST).join(' / ')}）`)
  }
  const text = textOverride ?? spec.text
  if (outcome === REPLAY_OUTCOME.FAIL && !text) {
    throw new Error('expectReplayToast：FAIL 结局必须传 opts.text（后端中文原文，逐字断言才有意义）')
  }

  // ① 该出现的 toast 出现了（级别 + 文案）
  await waitToast(page, spec.level, text, timeout)

  // ② 再多观察一小会儿：把「随后才冒出来的另一级提示」也录进来，避免抢跑判绿
  if (settleMs > 0) await page.waitForTimeout(settleMs)
  const seen = await recordedToasts(page)
  expect(
    seen.filter((t) => t.level === spec.level && (!text || t.text.includes(text))),
    `${spec.why}\n应出现 ${spec.level} 级提示，实际录到：${JSON.stringify(seen)}`
  ).not.toHaveLength(0)

  if (outcome === REPLAY_OUTCOME.DUPLICATE) {
    // ③ 绝不能有 error 级 ★ 这条就是「onError 写成 async」的回归门禁
    //    （async ⇒ 接管失效 ⇒ 真失败才该出现的红色进弹窗，重复通知被当成执行失败）
    await expectNoLevel(page, 'error', '重复通知不是错误：不许出现 error 级 toast')
    // ④ 也不能是 success：「已入账」只属于 RECORDED，绿色提示会把「没多记」说成「记上了」
    await expectNoLevel(page, 'success', '重复通知没有记账：不许出现 success 级 toast')

    // ⑤ onError 接管成功 ⇒ 弹窗必须收起。async 化之后 handled=false，弹窗会停在红色错误区
    await expect(
      dangerDialog(page),
      '重复通知被 onError 接管后确认弹窗必须收起（停在原地 = onError 没同步返回 true，'
        + '很可能是把它改成了 async）'
    ).toBeHidden({ timeout: 10_000 })
    // ⑥ 弹窗里的红色错误区不许留文案（v-if="dangerState.error"，收起了就不该有节点）
    await expect(dangerError(page), '弹窗已收起，红色错误区不该留文案').toHaveCount(0)

    // ⑦ 台账行数点前点后不变 —— DUPLICATE 的定义就是「流水未增加」
    if (rowsBefore != null) {
      await expect(
        ledgerRows(page),
        '重复通知不许增加流水：台账行数点前点后必须一致'
      ).toHaveCount(rowsBefore, { timeout: 15_000 })
    }
  }

  if (outcome === REPLAY_OUTCOME.RECORDED) {
    // ③ 成功路径只有绿色：不该同时冒 warning / error
    await expectNoLevel(page, 'warning', 'RECORDED 是真新增，不该出现 warning 级提示')
    await expectNoLevel(page, 'error', 'RECORDED 成功了，不该出现 error 级提示')
    // ④ 成功路径弹窗收起
    await expect(dangerDialog(page), '执行成功后确认弹窗应收起').toBeHidden({ timeout: 10_000 })
  }

  if (outcome === REPLAY_OUTCOME.FAIL) {
    // ③ 失败不关窗：原因留在弹窗里（与全站 14 个高危入口同口径）
    await expect(dangerDialog(page), '执行失败时弹窗**不许**关（原因要留在弹窗里）').toBeVisible()
    // ④ 弹窗红色错误区里就是后端原文，不打码不加工
    await expect(dangerError(page), `弹窗错误区应显示后端原文「${text}」`).toContainText(text)
    // ⑤ 失败时不该有 success 级提示
    await expectNoLevel(page, 'success', '失败路径不该出现 success 级提示')
  }

  return { toasts: seen }
}

/**
 * 把「点按钮 → 预演就绪 → 确认执行」这一段收成一次调用（纯流程，不含断言）。
 *
 * @param {object} [opts.willDo] 预演文案里应含的中文片段（默认取重放入口的那句）
 */
export const clickReplayAndConfirm = async (page, paymentId, { willDo = '再走一遍渠道回调', type = 'PAYMENT' } = {}) => {
  await replayButton(page, paymentId, type).click()
  await waitDanger(page, { willDo })
  await dangerOk(page).click()
}

/* ---------------------------------------------------------------------------
 * 与原先 /tmp 那份 11 条断言 harness 的对照（迁移说明，便于将来核对覆盖没丢）
 *
 * /tmp harness（stub element-plus，直接驱动真 useDangerConfirm）
 *   ① DUPLICATE → ElMessage.warning 被调用 1 次        → 这里 ① waitToast(warning)
 *   ② DUPLICATE → warning 文案含「重复通知，已忽略」     → 这里 ② 文案包含断言
 *   ③ DUPLICATE → ElMessage.error **零**调用           → 这里 ③ expectNoLevel(error)（录像机保证「从未出现」）
 *   ④ DUPLICATE → ElMessage.success **零**调用         → 这里 ④ expectNoLevel(success)
 *   ⑤ DUPLICATE → dangerState.visible === false        → 这里 ⑤ dangerDialog 收起（async 化后这条红）
 *   ⑥ DUPLICATE → dangerState.error === ''             → 这里 ⑥ dangerError 无节点
 *   ⑦ RECORDED  → ElMessage.success 含「渠道通知已入账」 → 这里 RECORDED ① + ②
 *   ⑧ RECORDED  → 无 warning / error                   → 这里 RECORDED ③
 *   ⑨ RECORDED  → 弹窗收起                             → 这里 RECORDED ④
 *   ⑩ code=1    → ElMessage.error 带后端原文           → 这里 FAIL ① + ④
 *   ⑪ code=1    → 弹窗不关、原因留在 error 区           → 这里 FAIL ③ + ④
 * 新增（DOM 侧比进程内多出来的一条，也是这条功能存在的意义）：
 *   ⑫ DUPLICATE 台账行数点前点后不变（`流水未增加` 的页面证据）
 * ------------------------------------------------------------------------- */
