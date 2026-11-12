/**
 * 保存失败的统一处理：把「409 版本冲突」和「400 缺版本号」翻译成用户能懂、且**不会静默丢改动**的交互。
 *
 * 为什么不能只 toast 一下：
 *  - 409 意味着后端 **一个字段都没写**（fail-closed 乐观锁），而用户屏幕上还留着自己改的内容；
 *    只弹一个 2 秒就消失的 toast，用户会以为「保存了」或者「手滑了」，接着关掉页面 —— 改动就真没了。
 *    所以这里弹一个必须被看见的对话框：说清「数据已被他人修改」，并给一个「刷新」按钮，
 *    点它 → 重新拉当前行/详情 → 重新填表单 → 用户在新数据上再改一遍。
 *  - 400「缺少版本号」是前端漏传 version 的 bug，用户无感但保存必然失败；
 *    这里除了提示「页面数据已过期，请刷新后重试」，还会**强制刷新**一次，把界面拉回真实状态。
 *
 * 用法（三个编辑入口一致）：
 *   try {
 *     await updateProduct(id, payload)
 *   } catch (e) {
 *     if (handleSaveError(e, { onRefresh: () => reloadForm() }) !== 'other') return
 *     ElMessage.error('保存失败：' + e.message)   // 普通错误自己兜底
 *   }
 */
import { reactive } from 'vue'
import { ElMessage } from 'element-plus'

/** 全站唯一的冲突弹窗状态（layout 里那一份 <ConflictDialog /> 渲染它） */
export const conflictState = reactive({
  visible: false,
  /** conflict = 409 被别人改过；outdated = 400 前端漏传版本号（页面数据已过期） */
  kind: '',
  title: '',
  message: '',
  hint: '',
  /** 刷新请求在飞 */
  resolving: false,
  _onRefresh: null
})

const reset = () => {
  conflictState.kind = ''
  conflictState.title = ''
  conflictState.message = ''
  conflictState.hint = ''
  conflictState.resolving = false
  conflictState._onRefresh = null
}

/** 关闭冲突弹窗（放弃刷新：用户自己决定怎么办） */
export const closeConflict = () => {
  conflictState.visible = false
  reset()
}

/**
 * 用户点「刷新」：重新拉当前行/详情并重填表单，让用户在新数据上重新改。
 * @param {{close?: boolean}} [opts] close=false 时不关窗（400 的强制刷新用它，好让提示留在屏幕上）
 */
export const refreshAfterConflict = async ({ close = true } = {}) => {
  const fn = conflictState._onRefresh
  if (!fn || conflictState.resolving) return
  conflictState.resolving = true
  try {
    await fn()
    if (close) {
      closeConflict()
      ElMessage.success('已刷新为最新数据，请重新修改后再保存')
    } else {
      conflictState.hint = '已重新拉取最新数据，表单已是最新内容，请确认后重新保存。'
    }
  } catch (e) {
    ElMessage.error('刷新失败：' + (e?.message || e))
  } finally {
    conflictState.resolving = false
  }
}

/**
 * 保存失败的统一入口。
 *
 * @param {any} err 请求错误（api/request.js 已把业务 code 挂在 err.bizCode 上）
 * @param {{onRefresh?: () => any}} [opts] 重新拉取当前行/详情并重填表单
 * @returns {'conflict'|'outdated'|'other'} conflict/outdated 表示已经接管（调用方不要再自行 toast）
 */
export function handleSaveError(err, { onRefresh } = {}) {
  const code = err?.bizCode
  if (code === 409) {
    conflictState.visible = true
    conflictState.kind = 'conflict'
    conflictState.title = '数据已被他人修改'
    conflictState.message = '数据已被他人修改（可能其他人在编辑）'
    conflictState.hint = '你刚才填写的内容没有被保存。点「刷新」拉取最新数据后，请重新修改一次。'
    conflictState._onRefresh = onRefresh || null
    return 'conflict'
  }
  if (code === 400) {
    // 400 = 前端漏传 version（自己的 bug）：不商量，强制把界面拉回真实状态
    conflictState.visible = true
    conflictState.kind = 'outdated'
    conflictState.title = '页面数据已过期'
    conflictState.message = '页面数据已过期，请刷新后重试'
    conflictState.hint = '正在自动为你重新拉取最新数据…'
    conflictState._onRefresh = onRefresh || null
    refreshAfterConflict({ close: false })
    return 'outdated'
  }
  // 其他错误：后端中文原因直接给用户（调用方通常还会补一句上下文）
  ElMessage.error(err?.message || '保存失败')
  return 'other'
}
