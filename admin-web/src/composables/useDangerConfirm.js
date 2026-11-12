/**
 * 高危写操作的统一交互：「预演 → 展示将要发生什么 → 确认 → 带码执行」。
 *
 * 为什么抽成 composable + 一个全局弹窗（而不是 14 份复制粘贴）：
 *  - 14 个操作的交互骨架完全一样，只有「预演接口 / 执行接口 / 成功后刷新什么」三处不同，
 *    这三处正好是可以当参数传进来的，没必要每个页面各写一套状态机 + 倒计时 + 错误处理；
 *  - 状态机只有一份 → 倒计时到点禁用、执行失败不关窗、码是一次性的这些边界只在一个地方改；
 *  - 弹窗组件在 layout 里挂**一次**（全站单例），页面里没有任何弹窗模板代码，
 *    页面调用的就是 `await danger.run({ dryRun, exec, onSuccess })` 三行。
 *
 * 为什么不写成「14 个页面各放一个 <DangerConfirmDialog ref>」：
 * 那样每个页面都要管「打开/关闭/失败后保留/成功后刷新」，容易某个页面漏掉「执行失败不关窗」，
 * 而这恰恰是用户最容易踩到的一条（比如「方案已变化」）。这里把它收进状态机里，
 * 页面就算什么都不做也不会漏。
 *
 * 后端契约（14 个 L3 写接口一致）：
 *  ① 预演：同一路径 + ?dryRun=true → { code:0, data:{ willDo, confirmCode, expiresInSec } }，库里零变化
 *  ② 执行：原路径 + body { confirmCode } → 缺码/无效/已用/过期/目标不符/指纹变化 一律 { code:1, message:中文原因 }
 */
import { reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'

/** 默认有效期兜底：后端没给 expiresInSec 时按契约的 300 秒算，绝不无限等 */
const DEFAULT_EXPIRES_IN_SEC = 300

/** 全站唯一的弹窗状态（layout 里那一份 <DangerConfirmDialog /> 渲染它） */
export const dangerState = reactive({
  visible: false,
  title: '',
  willDo: '',
  /** 可选的「明细」区：[{ label, value }]，例如审核通过的拆单方案（哪个仓、几条明细） */
  details: [],
  confirmText: '确认执行',
  successText: '',
  confirmCode: '',
  expiresInSec: 0,
  remaining: 0,
  /** 预演进行中：弹窗先开、骨架文案先显示，避免点完按钮「没有任何反应」 */
  previewing: false,
  executing: false,
  error: '',
  /** 倒计时归零 */
  expired: false,
  /** 执行失败过一次：确认码已被后端取出即删，同一个码不可能再用 → 只能重新预演 */
  consumed: false,
  /** 内部：本次要执行的动作与回调 */
  _exec: null,
  _onSuccess: null,
  _resolve: null
})

let timer = null
/** 倒计时截止时刻（毫秒）。用绝对时间算剩余秒，避免 setInterval 漂移导致「显示的比真实的多几秒」 */
let deadline = 0

const stopTimer = () => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

const tick = () => {
  dangerState.remaining = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
  if (dangerState.remaining <= 0) {
    dangerState.expired = true
    stopTimer()
  }
}

/** 清空一次交互的全部状态（不调用后端） */
const clear = () => {
  stopTimer()
  deadline = 0
  Object.assign(dangerState, {
    visible: false,
    title: '',
    willDo: '',
    details: [],
    confirmText: '确认执行',
    successText: '',
    confirmCode: '',
    expiresInSec: 0,
    remaining: 0,
    previewing: false,
    executing: false,
    error: '',
    expired: false,
    consumed: false,
    _exec: null,
    _onSuccess: null
  })
}

/** 结束本次交互：resolve 出去给页面（true=执行成功，false=取消/失败后关闭） */
const finish = (ok) => {
  const resolve = dangerState._resolve
  clear()
  dangerState._resolve = null
  if (resolve) resolve(ok)
}

/** 「确认执行」当前是否可点 */
const canExecute = computed(
  () =>
    dangerState.visible &&
    !dangerState.previewing &&
    !dangerState.executing &&
    !!dangerState.confirmCode &&
    !dangerState.expired &&
    !dangerState.consumed
)

/** 倒计时文案 mm:ss */
const countdownText = computed(() => {
  const s = Math.max(0, dangerState.remaining)
  const mm = String(Math.floor(s / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return `${mm}:${ss}`
})

/**
 * 取消：关弹窗，**不发任何请求**。
 * 后端签出的码没人用，5 分钟后自己过期（服务端 TTL），前端什么都不用做。
 */
const cancel = () => {
  if (dangerState.executing) return // 请求在飞，等它回来再说
  if (dangerState.visible) finish(false)
}

/**
 * 确认执行：带 confirmCode 调真接口。
 * - 成功：关弹窗 + 提示 + 刷新（onSuccess）
 * - 失败：**不关弹窗**，把后端中文原因显示在弹窗里（用户能看着原因决定关不关）
 */
const execute = async () => {
  if (!canExecute.value) return
  dangerState.executing = true
  dangerState.error = ''
  try {
    await dangerState._exec(dangerState.confirmCode)
    const text = dangerState.successText || '操作成功'
    const onSuccess = dangerState._onSuccess
    finish(true)
    ElMessage.success(text)
    if (onSuccess) {
      try {
        await onSuccess()
      } catch (e) {
        ElMessage.error('刷新列表失败：' + e.message)
      }
    }
  } catch (e) {
    // 码是一次性的：这一次请求已经把码消费掉了，再点「确认执行」只会回「确认码无效/已使用」
    dangerState.error = e?.message || '执行失败'
    dangerState.consumed = true
  } finally {
    dangerState.executing = false
  }
}

/**
 * 跑一次「预演 → 确认 → 带码执行」。
 *
 * @param {() => Promise<any>} dryRun 预演接口（同一个路径 + ?dryRun=true），要返回 {willDo, confirmCode, expiresInSec}
 * @param {(confirmCode: string) => Promise<any>} exec 执行接口（原路径 + body 带 confirmCode）
 * @param {() => any} [onSuccess] 执行成功后的刷新（关窗 + 成功提示之后调用）
 * @param {string} [title] 弹窗标题
 * @param {string} [confirmText] 确认按钮文案
 * @param {string} [successText] 成功 toast 文案
 * @param {(preview: any) => Array<{label:string,value:string}>} [details] 把预演返回的额外信息（如拆单方案）转成明细区
 * @returns {Promise<boolean>} true=执行成功；false=用户取消，或失败后关闭弹窗
 */
const run = async ({ dryRun, exec, onSuccess, title, confirmText, successText, details } = {}) => {
  clear()
  // 先把弹窗开起来显示「正在预演」：预演也是一次网络请求，不给反馈的话用户会以为按钮点了没用
  dangerState.visible = true
  dangerState.title = title || '⚠️ 确认执行这次操作'
  dangerState.previewing = true
  dangerState.confirmText = confirmText || '确认执行'
  dangerState.successText = successText || ''

  const done = new Promise((resolve) => {
    dangerState._resolve = resolve
  })

  try {
    const preview = (await dryRun()) || {}
    if (!preview.confirmCode) {
      throw new Error('预演没有返回确认码（后端契约异常），无法执行')
    }
    dangerState.willDo = preview.willDo || '（后端没有说明将要发生什么）'
    dangerState.details = (details ? details(preview) : null) || []
    dangerState.confirmCode = preview.confirmCode
    dangerState.expiresInSec = Number(preview.expiresInSec) > 0
      ? Number(preview.expiresInSec)
      : DEFAULT_EXPIRES_IN_SEC
    dangerState._exec = exec
    dangerState._onSuccess = onSuccess || null
    // 倒计时从「预演返回的这一刻」开始算：码就是这一刻签发的
    deadline = Date.now() + dangerState.expiresInSec * 1000
    dangerState.remaining = dangerState.expiresInSec
    dangerState.expired = false
    dangerState.consumed = false
    stopTimer()
    timer = setInterval(tick, 250)
    tick()
  } catch (e) {
    // 预演就失败（守卫不通过 / 后端报错）：直接告诉用户，不弹一个空白确认框
    ElMessage.error('预演失败：' + (e?.message || e))
    finish(false)
  } finally {
    dangerState.previewing = false
  }

  return done
}

export function useDangerConfirm() {
  return { run, cancel, execute, state: dangerState, canExecute, countdownText }
}
