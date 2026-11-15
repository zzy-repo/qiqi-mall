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
      <el-text class="conflict-message" tag="b" data-testid="conflict-message">{{ conflictState.message }}</el-text>
      <el-text v-if="conflictState.hint" class="conflict-hint" tag="p" size="small" type="info" data-testid="conflict-hint">{{ conflictState.hint }}</el-text>
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
/* 主句 = el-text tag="b"（700）+ 默认 14px；原来钉 15px/600/墨色三件。
   "这是主句"由 tag="b" 与对话框标题共同表达，不必再造一个 15px 档。 */
.conflict-message {
  margin: 0;
  line-height: 1.7;
  word-break: break-word;
}
/* 副句 12px 走 size="small"（模板里已给），不再钉 13px */
.conflict-hint {
  margin: 10px 0 0;
  line-height: 1.7;
}
</style>
