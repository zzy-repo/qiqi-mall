/**
 * 高危写操作的统一交互：「预演 → 展示将要发生什么 → 确认 → 执行」。
 *
 * 为什么抽成 composable + 一个全局弹窗（而不是 14 份复制粘贴）：
 *  - 14 个操作的交互骨架完全一样，只有「预演接口 / 执行接口 / 成功后刷新什么」三处不同，
 *    这三处正好是可以当参数传进来的，没必要每个页面各写一套状态机 + 错误处理；
 *  - 状态机只有一份 → 「执行失败不关窗、取消不发请求、成功后刷新」这些边界只在一个地方改；
 *  - 弹窗组件在 layout 里挂**一次**（全站单例），页面里没有任何弹窗模板代码，
 *    页面调用的就是 `await danger.run({ dryRun, exec, onSuccess })` 三行。
 *
 * 为什么不写成「14 个页面各放一个 <DangerConfirmDialog ref>」：
 * 那样每个页面都要管「打开/关闭/失败后保留/成功后刷新」，容易某个页面漏掉「执行失败不关窗」，
 * 而这恰恰是用户最容易踩到的一条（比如「方案已变化」）。这里把它收进状态机里，
 * 页面就算什么都不做也不会漏。
 *
 * 弹窗之所以留下来，是因为「先看清将要发生什么再确认」本身有价值 —— 它和确认码是两件事。
 *
 * 后端契约（现行，确认码已下线）：
 *  ① 预演：同一路径 + ?dryRun=true → { code:0, data:{ willDo, ... } }，库里零变化，
 *     **不再签发任何确认码**（没有有效期、没有倒计时、没有一次性作废）；
 *  ② 执行：原路径 + 业务 body；并发保护交给行级 version（乐观锁，见 OptimisticLockGuard），
 *     需要 version 的入口由调用方从行数据取，通过 run() 的 `version` 参数传进来；
 *  ③ 失败一律 { code:1 / 400 / 409, message:中文原因 }，弹窗里原样展示。
 */
import { reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'

/** 全站唯一的弹窗状态（layout 里那一份 <DangerConfirmDialog /> 渲染它） */
export const dangerState = reactive({
  visible: false,
  title: '',
  willDo: '',
  /** 可选的「明细」区：[{ label, value }]，例如审核通过的拆单方案（哪个仓、几条明细） */
  details: [],
  confirmText: '确认执行',
  successText: '',
  /** 预演进行中：弹窗先开、骨架文案先显示，避免点完按钮「没有任何反应」 */
  previewing: false,
  executing: false,
  error: '',
  /** 本次执行要带的行级版本号（乐观锁）；纯状态流转类接口没有版本号，传 undefined 即不带 */
  version: undefined,
  /** 内部：本次要执行的动作与回调 */
  _exec: null,
  _onSuccess: null,
  _onError: null,
  _resolve: null
})

/** 清空一次交互的全部状态（不调用后端） */
const clear = () => {
  Object.assign(dangerState, {
    visible: false,
    title: '',
    willDo: '',
    details: [],
    confirmText: '确认执行',
    successText: '',
    previewing: false,
    executing: false,
    error: '',
    version: undefined,
    _exec: null,
    _onSuccess: null,
    _onError: null
  })
}

/** 结束本次交互：resolve 出去给页面（true=执行成功，false=取消/失败后关闭） */
const finish = (ok) => {
  const resolve = dangerState._resolve
  clear()
  dangerState._resolve = null
  if (resolve) resolve(ok)
}

/** 「确认执行」当前是否可点：预演回来了、没有请求在飞 */
const canExecute = computed(
  () => dangerState.visible && !dangerState.previewing && !dangerState.executing && !!dangerState._exec
)

/**
 * 取消：关弹窗，**不发任何请求**。
 */
const cancel = () => {
  if (dangerState.executing) return // 请求在飞，等它回来再说
  if (dangerState.visible) finish(false)
}

/**
 * 确认执行：调用真接口。
 * - 成功：关弹窗 + 提示 + 刷新（onSuccess）
 * - 失败：**不关弹窗**，把后端中文原因显示在弹窗里（用户能看着原因决定关不关）；
 *   若调用方给了 onError 且它返回 true（例如 409 冲突已交给统一冲突弹窗处理），则由它接管、本次收起。
 */
const execute = async () => {
  if (!canExecute.value) return
  dangerState.executing = true
  dangerState.error = ''
  try {
    await dangerState._exec(dangerState.version)
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
    let handled = false
    if (dangerState._onError) {
      try {
        handled = dangerState._onError(e) === true
      } catch (hookError) {
        handled = false
      }
    }
    if (handled) {
      dangerState.executing = false
      finish(false)
      return
    }
    dangerState.error = e?.message || '执行失败'
  } finally {
    dangerState.executing = false
  }
}

/**
 * 跑一次「预演 → 确认 → 执行」。
 *
 * @param {() => Promise<any>} dryRun 预演接口（同一个路径 + ?dryRun=true），要返回 { willDo, ... }
 * @param {(version?: any) => Promise<any>} exec 执行接口；需要乐观锁的入口用 version 拼进 body
 * @param {any} [version] 行级版本号（乐观锁）；exec 会收到它，纯状态流转类接口不用给
 * @param {() => any} [onSuccess] 执行成功后的刷新（关窗 + 成功提示之后调用）
 * @param {(err: any) => boolean} [onError] 执行失败时的接管钩子：返回 true 表示「已自行处理」（弹窗收起）
 * @param {string} [title] 弹窗标题
 * @param {string} [confirmText] 确认按钮文案
 * @param {string} [successText] 成功 toast 文案
 * @param {(preview: any) => Array<{label:string,value:string}>} [details] 把预演返回的额外信息（如拆单方案）转成明细区
 * @returns {Promise<boolean>} true=执行成功；false=用户取消，或失败后关闭弹窗
 */
const run = async ({
  dryRun, exec, onSuccess, onError, version, title, confirmText, successText, details
} = {}) => {
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
    dangerState.willDo = preview.willDo || '（后端没有说明将要发生什么）'
    dangerState.details = (details ? details(preview) : null) || []
    dangerState.version = version
    dangerState._exec = exec
    dangerState._onSuccess = onSuccess || null
    dangerState._onError = onError || null
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
  return { run, cancel, execute, state: dangerState, canExecute }
}
