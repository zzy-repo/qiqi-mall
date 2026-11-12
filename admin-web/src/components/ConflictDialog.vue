<template>
  <el-dialog
    v-model="visible"
    :title="conflictState.title || '数据已被他人修改'"
    width="480px"
    align-center
    append-to-body
    :close-on-click-modal="!conflictState.resolving"
    :close-on-press-escape="!conflictState.resolving"
    data-testid="conflict-dialog"
  >
    <div class="conflict-body">
      <!-- 主文案：409=「数据已被他人修改（可能其他人在编辑）」，400=「页面数据已过期，请刷新后重试」 -->
      <p class="conflict-message" data-testid="conflict-message">{{ conflictState.message }}</p>
      <p v-if="conflictState.hint" class="conflict-hint" data-testid="conflict-hint">{{ conflictState.hint }}</p>
    </div>

    <template #footer>
      <el-button data-testid="conflict-cancel" :disabled="conflictState.resolving" @click="closeConflict">
        {{ conflictState.kind === 'outdated' ? '知道了' : '先不刷新' }}
      </el-button>
      <el-button
        type="primary"
        data-testid="conflict-refresh"
        :loading="conflictState.resolving"
        @click="refreshAfterConflict()"
      >刷新</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 保存冲突弹窗（全站单例，挂载在 layout/index.vue）。
 *
 * 只做渲染：状态与流程在 composables/useConflictHandler.js。
 * 为什么不做成 ElMessageBox.confirm：那个弹窗塞不进稳定的 data-testid，
 * 而「冲突提示 + 刷新按钮」是这次乐观锁改造的核心交互，必须能被 E2E 断言到。
 */
import { computed } from 'vue'
import { conflictState, closeConflict, refreshAfterConflict } from '../composables/useConflictHandler'

const visible = computed({
  get: () => conflictState.visible,
  set: (v) => {
    if (!v) closeConflict()
  }
})
</script>

<style scoped>
.conflict-body { min-height: 48px; }
.conflict-message {
  margin: 0;
  font-size: 15px;
  line-height: 1.7;
  color: #303133;
  font-weight: 600;
  word-break: break-word;
}
.conflict-hint {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: #606266;
}
</style>
