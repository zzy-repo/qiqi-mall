<template>
  <el-dialog
    v-model="visible"
    :title="dangerState.title || '⚠️ 确认执行这次操作'"
    width="560px"
    align-center
    append-to-body
    data-testid="danger-confirm-dialog"
    :close-on-click-modal="!dangerState.executing"
    :close-on-press-escape="!dangerState.executing"
    :show-close="!dangerState.executing"
  >
    <div v-loading="dangerState.previewing" class="danger-body">
      <!-- willDo：后端用「人话」说明将要发生什么（带单号 / 名称 / 数量）。
           正常字号、可选中复制：用户要能一眼读清、也能截给同事看。 -->
      <p class="willdo" data-testid="danger-confirm-willdo">
        {{ dangerState.previewing ? '正在预演，看看将要发生什么…' : dangerState.willDo }}
      </p>

      <!-- 可选明细区：例如「审核通过」的拆单方案（哪个仓、几条明细、分别是哪些商品） -->
      <div v-if="dangerState.details.length" class="details" data-testid="danger-confirm-details">
        <div v-for="(d, i) in dangerState.details" :key="i" class="detail-row">
          <span class="detail-label">{{ d.label }}</span>
          <span class="detail-value">{{ d.value }}</span>
        </div>
      </div>

      <!-- 执行失败：不关弹窗，直接把后端的中文原因摆在这里 -->
      <el-alert
        v-if="dangerState.error"
        class="tip"
        type="error"
        :closable="false"
        show-icon
        :title="dangerState.error"
        data-testid="danger-confirm-error"
      />
    </div>

    <template #footer>
      <el-button data-testid="danger-confirm-cancel" :disabled="dangerState.executing" @click="cancel">取消</el-button>
      <el-button
        type="danger"
        data-testid="danger-confirm-ok"
        :disabled="!canExecute"
        :loading="dangerState.executing"
        @click="execute"
      >{{ dangerState.confirmText }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 高危写操作的统一确认弹窗（全站单例，挂载在 layout/index.vue）。
 *
 * 它只做渲染：状态与流程都在 composables/useDangerConfirm.js 里，
 * 所以 14 个调用点不需要各自复制「失败不关窗 / 成功后刷新」这套逻辑。
 *
 * 注意：确认码已下线（后端同批删除），所以这里不再有倒计时、有效期、一次性作废的展示，
 * 也没有「码已失效，请重新预演」这类文案 —— 并发保护改由行级 version（乐观锁）承担。
 */
import { computed } from 'vue'
import { useDangerConfirm } from '../composables/useDangerConfirm'

const { state: dangerState, cancel, execute, canExecute } = useDangerConfirm()

// el-dialog 的 v-model：用户点右上角 ×（或遮罩，若允许）时等价于「取消」——不发请求
const visible = computed({
  get: () => dangerState.visible,
  set: (v) => {
    if (!v) cancel()
  }
})
</script>

<style scoped>
.danger-body { min-height: 80px; }
/* willDo 用正常字号正文样式：不缩小、不变灰，允许换行，允许选中复制 */
.willdo {
  margin: 0;
  font-size: 15px;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
  user-select: text;
}
.details {
  margin: 12px 0 0;
  padding: 8px 12px;
  background: #f7f8fa;
  border-radius: 4px;
  border-left: 3px solid #e6a23c;
}
.detail-row { display: flex; gap: 10px; font-size: 13px; line-height: 1.8; user-select: text; }
.detail-label { flex: 0 0 auto; color: #606266; font-weight: 600; }
.detail-value { flex: 1; color: #303133; word-break: break-word; }
/* 失败时留一点呼吸：倒数第二块（willdo / details）与错误条之间 */
.tip { margin-top: 12px; }
</style>
