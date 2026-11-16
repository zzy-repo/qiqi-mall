<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <div>
          <span>自动审核策略</span>
          <span class="header-tip">
            判定链顺序固定：<b>阻止 → 异常冻结 → 自动过审</b>；同类型内按优先级升序（数字小的先判）
          </span>
        </div>
        <div class="header-actions">
          <el-button :icon="Refresh" data-testid="policy-refresh" @click="load">刷新</el-button>
          <el-button type="primary" :icon="Plus" data-testid="policy-create" @click="openEditor(null)">新建策略</el-button>
        </div>
      </div>
    </template>

    <el-alert
      v-if="metaError"
      type="error"
      :closable="false"
      show-icon
      :title="`字段/操作符元数据加载失败：${metaError}`"
      description="数据从 GET /admin/audit-policies 的 fields / operators 取（后端 FieldRegistry 是唯一来源）。页面不内置字段清单，取不到就不让配，避免配出永远不命中的条件。"
    />

    <el-tabs v-model="activeType" data-testid="policy-tabs">
      <!-- 优先级的作用域：只在同类型内比较。跨类型顺序写死在引擎里（AuditEngine：BLOCK → EXCEPTION → AUTO），
           把异常策略 priority 调到 5 也抢不过阻止策略 —— 不写明就会有人以为「数字小就一定先判」。
           放在 default slot 里 = 渲染在 tab 栏下方、表格上方，且全局只有这一行（不随 tab 复制）。 -->
      <el-alert
        class="priority-note"
        type="info"
        :closable="false"
        show-icon
        data-testid="policy-priority-scope-note"
      >
        优先级只在<b>同类型</b>策略之间比较；跨类型的判定顺序固定为：阻止 → 异常 → 自动
      </el-alert>
      <el-tab-pane
        v-for="t in TYPES"
        :key="t"
        :name="t"
        :label="`${auditPolicyTypeText(t)}（${policiesOf(t).length}）`"
        :data-testid="`policy-tab-${t}`"
      >
        <el-text class="tab-tip" tag="div" size="small" type="info">{{ AUDIT_POLICY_TYPE_TIP[t] }}</el-text>

        <el-table
          v-loading="loading"
          :data="policiesOf(t)"
          :row-key="(row) => `${row.type}-${row.id}`"
          border
          empty-text="还没有这类策略"
          :data-testid="`policy-table-${t}`"
        >
          <el-table-column label="优先级" width="80" align="center">
            <template #default="{ row }"><b>{{ row.priority }}</b></template>
          </el-table-column>

          <el-table-column label="名称" min-width="170">
            <template #default="{ row }">
              <b :data-testid="`policy-row-${row.id}`" class="policy-name">{{ row.name }}</b>
              <div class="muted">ID {{ row.id }}</div>
            </template>
          </el-table-column>

          <el-table-column label="启用" width="80" align="center">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled"
                :data-testid="`policy-enabled-${row.id}`"
                @change="(v) => toggleEnabled(row, v)"
              />
            </template>
          </el-table-column>

          <el-table-column label="版本" width="80" align="center">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">v{{ row.versionNo }}</el-tag>
            </template>
          </el-table-column>

          <!-- 条件摘要：一条条件一个 chip，chip 的 testid 就是 why-miss-${policyId}-${seq}
               （从队列「判定依据」跳过来时，命中那条会被高亮并标出「← 此项未满足」） -->
          <el-table-column label="条件摘要" min-width="330">
            <template #default="{ row }">
              <div v-if="row.conditions.length" class="cond-chips">
                <el-tag
                  v-for="c in row.conditions"
                  :key="c.seq"
                  class="cond-chip"
                  size="small"
                  effect="plain"
                  :type="chipType(row.id, c)"
                  :data-testid="`why-miss-${row.id}-${c.seq}`"
                  :title="`第 ${c.seq} 条条件`"
                >
                  <el-text v-if="isFocused(row.id, c.seq)" tag="b" type="danger">← 此项未满足</el-text>
                  <template v-if="c.kind === 'EXCLUDE'">排除 </template>{{ conditionSummary(c) }}
                </el-tag>
              </div>
              <span v-else class="muted">无条（命不中任何单）</span>
            </template>
          </el-table-column>

          <el-table-column label="动作" min-width="200">
            <template #default="{ row }">{{ actionText(row) }}</template>
          </el-table-column>

          <el-table-column label="最近修改" width="150">
            <template #default="{ row }">
              <span v-if="row.updatedAt" class="datetime">{{ row.updatedAt }}</span>
              <!-- 后端 PolicyDto 目前没有 updatedAt：如实显示「—」，不编一个假时间 -->
              <span v-else class="muted" title="后端策略出参未返回 updatedAt">—</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row, $index }">
              <el-button
                link
                type="primary"
                size="small"
                :disabled="$index === 0"
                :data-testid="`policy-move-up-${row.id}`"
                @click="move(row, 'UP')"
              >上移</el-button>
              <el-button
                link
                type="primary"
                size="small"
                :disabled="$index === policiesOf(t).length - 1"
                :data-testid="`policy-move-down-${row.id}`"
                @click="move(row, 'DOWN')"
              >下移</el-button>
              <el-button link type="primary" size="small" :data-testid="`policy-edit-${row.id}`" @click="openEditor(row)">编辑</el-button>
              <el-button link type="danger" size="small" :data-testid="`policy-delete-${row.id}`" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 全局排除：不分策略，所有 AUTO 策略判定时都会并进来（保存在任一自动策略里一并提交） -->
        <el-card v-if="t === 'AUTO'" shadow="never" class="global-card" data-testid="policy-global-exclusions">
          <template #header>
            <div class="global-head">
              <span>全局排除（对所有自动策略生效）</span>
              <span class="muted">
                判定时每条 AUTO 策略都会并入这些条件；编辑入口在任意一条自动策略的编辑弹窗里
              </span>
            </div>
          </template>
          <div v-if="globalExclusions.length" class="cond-chips">
            <el-tag v-for="g in globalExclusions" :key="g.seq" class="cond-chip" size="small" effect="plain" type="danger">
              排除 {{ conditionSummary(g) }}
            </el-tag>
          </div>
          <div v-else class="muted">还没有配置全局排除</div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- ================= 编辑弹窗 ================= -->
    <el-dialog
      v-model="editorVisible"
      :title="editing.id ? `编辑策略 - ${editing.name}` : '新建策略'"
      width="min(900px, 94vw)"
      top="6vh"
      data-testid="policy-editor"
    >
      <el-form label-width="96px" size="small">
        <!-- 首行四个字段的宽度分配：md 档 7+8+5+4=24 保证「优先级」有真实可用宽度
             （span=4 时 131px 里 96px 被标签吃掉，内层 input 被压成 0 宽 → 看不见、点不到）；
             xs/sm 档折行：窄屏下 类型/名称 各占整行、优先级/启用 并排一行 -->
        <div class="form-row">
          <el-row :gutter="12">
            <el-col :xs="24" :sm="12" :md="7">
              <el-form-item label="类型">
                <el-select v-model="editing.type" data-testid="policy-form-type">
                  <el-option v-for="t in TYPES" :key="t" :label="auditPolicyTypeText(t)" :value="t" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="12" :md="8">
              <el-form-item label="名称">
                <el-input v-model="editing.name" data-testid="policy-form-name" placeholder="同类型内唯一" />
              </el-form-item>
            </el-col>
            <el-col :xs="14" :sm="6" :md="5">
              <!-- 标签宽度按内容（3 个字 ≈ 42px）单独收窄，别跟着表单的 96px 吃宽度 -->
              <el-form-item label="优先级" label-width="56px">
                <!-- el-input-number 默认 150px，EP 没有 width prop，这一处要铺满栅格列只能留内联宽度 -->
                <el-input-number v-model="editing.priority" :min="1" :max="9999" controls-position="right" data-testid="policy-form-priority" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :xs="10" :sm="6" :md="4">
              <el-form-item label="启用" label-width="56px">
                <el-switch v-model="editing.enabled" data-testid="policy-form-enabled" />
              </el-form-item>
            </el-col>
          </el-row>
        </div>

        <div v-if="editing.type === 'EXCEPTION'" class="form-row">
          <el-row :gutter="12">
            <el-col :xs="24" :sm="10">
              <el-form-item label="冻结标码">
                <el-input v-model="editing.actionTag" data-testid="policy-form-action-tag" placeholder="FROZEN" />
              </el-form-item>
            </el-col>
            <el-col :xs="24" :sm="14">
              <!-- 原因从字典里选：这条策略冻出来的单靠 reason_code 才能被统计、才能做 P1 的豁免键。
                   一句自由文本会让「这个月为什么冻了 50 单」再次算不清（P2 之前就是这样）。 -->
              <el-form-item label="冻结原因">
                <el-select
                  v-model="editing.actionReasonCode"
                  data-testid="policy-form-action-reason"
                  clearable
                  filterable
                  placeholder="选一条原因（统计与豁免都以它为准）"
                  style="width: 100%"
                >
                  <el-option v-for="r in freezeReasonOptions" :key="r.code" :label="r.name" :value="r.code" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>
        </div>

        <!-- 命中时打标（期 2 步骤 2）：**并行动作**，三种策略类型都能配。
             它是「策略 A 打标 → 下一轮策略 B 读标」这条传送带的起点，
             所以选项只来自标目录 category='特征' 且启用的项（后端保存时也按同一口径校验）。 -->
        <div class="form-row">
          <el-form-item label="命中时打标">
            <div class="feature-tag-picker">
              <el-select
                v-model="editing.featureTags"
                multiple
                filterable
                collapse-tags
                collapse-tags-tooltip
                data-testid="policy-form-feature-tags"
                :placeholder="featureTagOptions.length ? '选特征标（可多选，最多 5 个）' : '目录里还没有特征标，先点右侧新增'"
                style="width: 100%"
              >
                <el-option
                  v-for="t in featureTagOptions"
                  :key="t.code"
                  :label="t.name"
                  :value="t.code"
                >
                  <span>{{ t.name }}</span>
                  <el-text size="small" type="info">{{ t.code }}</el-text>
                </el-option>
              </el-select>
              <el-button size="small" :icon="Plus" data-testid="feature-tag-create" @click="openTagCreator">新增特征标</el-button>
            </div>
            <div class="muted cond-tip">
              命中时<b>顺手</b>打这些标，不改变结论（该过的照样过、该拦的照样拦）。
              特征标是策略之间的传送带：下一条策略可以用「当前标记 有标 X」读它。
            </div>
          </el-form-item>
        </div>

        <el-form-item label="匹配条件">
          <div class="cond-editor" data-testid="cond-editor">
            <div v-if="!editing.conditions.length" class="muted">还没有条件：一条条件都没有的策略永远不会命中</div>

            <!-- 一行一条条件：列宽由 el-col 的 :span 按比例给，不再给每个控件写死 px。
                 旧写法（写死 86/190/110/130/240 + flex）在窄弹窗里必然横向溢出，
                 「优先级被压成 0 宽」就是同一类问题；栅格化后是「先分列、控件铺满列」，
                 窄屏只会换行，不会挤出弹窗。 -->
            <el-card
              v-for="(c, i) in editing.conditions"
              :key="`cond-${i}`"
              :data-testid="`cond-row-${i}`"
              class="cond-row" shadow="never" :body-style="condRowBodyStyle"
            >
              <el-row :gutter="8">
                <el-col :span="1"><el-text class="cond-seq" tag="code" size="small" type="info">{{ i }}</el-text></el-col>

                <el-col :xs="24" :sm="6" :md="3">
                  <el-select v-model="c.kind" size="small" :data-testid="`cond-kind-${i}`">
                    <el-option label="匹配" value="MATCH" />
                    <el-option label="排除" value="EXCLUDE" />
                  </el-select>
                </el-col>

                <!-- 字段下拉：选项来自后端 FieldRegistry（GET /admin/audit-policies 的 fields）。
                     字段码/值类型用小字跟在名称后面，交给 el-text 上色，不再手写 .opt-code -->
                <el-col :xs="24" :sm="10" :md="6">
                  <el-select
                    v-model="c.fieldCode"
                    size="small"
                    filterable
                    placeholder="字段"
                    :data-testid="`cond-field-${i}`"
                    @change="(v) => onFieldChange(c, v)"
                  >
                    <el-option v-for="f in fieldsMeta" :key="f.code" :label="f.label" :value="f.code">
                      <span>{{ f.label }}</span>
                      <el-text size="small" type="info">{{ f.code }} · {{ f.valueType }}</el-text>
                    </el-option>
                  </el-select>
                </el-col>

                <!-- 操作符：按字段值类型过滤（NUM 才有 LT/GT…，TEXT 才有 含/不含，布尔只有 =/≠） -->
                <el-col :xs="24" :sm="6" :md="4">
                  <el-select v-model="c.opCode" size="small" :data-testid="`cond-op-${i}`">
                    <el-option
                      v-for="op in opsOf(c)"
                      :key="op"
                      :label="`${auditOpText(op)} ${op}`"
                      :value="op"
                    />
                  </el-select>
                </el-col>

                <!-- 值控件：按值类型切换（数字 / 文本 / 布尔）；EXISTS/NOT_EXISTS 不需要值 -->
                <el-col :xs="24" :sm="12" :md="7">
                  <template v-if="needsValue(c)">
                    <el-input-number
                      v-if="typeOf(c) === 'NUM'"
                      v-model="c.valueNum"
                      size="small"
                      :controls="false"
                      placeholder="数值"
                      :data-testid="`cond-value-num-${i}`"
                    />
                    <el-select
                      v-else-if="typeOf(c) === 'BOOL'"
                      v-model="c.valueNum"
                      size="small"
                      :data-testid="`cond-value-bool-${i}`"
                    >
                      <el-option label="是" :value="1" />
                      <el-option label="否" :value="0" />
                    </el-select>
                    <el-input
                      v-else
                      v-model="c.valueText"
                      size="small"
                      :placeholder="typeOf(c) === 'LIST' ? '标码（精确匹配，不按子串）' : '文本（含/不含 按原样比较，% 不是通配符）'"
                      :data-testid="`cond-value-text-${i}`"
                    />
                  </template>
                  <span v-else class="muted">（该操作符不需要值）</span>
                </el-col>

                <el-col :span="3">
                  <el-button link type="danger" size="small" :data-testid="`cond-remove-${i}`" @click="removeCondition(i)">删除</el-button>
                </el-col>
              </el-row>
            </el-card>

            <el-button size="small" :icon="Plus" data-testid="cond-add" @click="addCondition">添加条件</el-button>
            <div class="muted cond-tip">
              多条条件之间是 <b>AND</b>：全部满足才算命中。EXCLUDE 条件只要成立，这条策略就被排除。
            </div>
          </div>
        </el-form-item>

        <!-- 全局排除：只在自动策略里编辑（后端没有独立端点，随策略保存整组替换） -->
        <el-form-item v-if="editing.type === 'AUTO'" label="全局排除">
          <div class="cond-editor" data-testid="global-cond-editor">
            <div v-if="!editing.globalExclusions.length" class="muted">没有全局排除条件</div>
            <el-card
              v-for="(c, i) in editing.globalExclusions"
              :key="`global-${i}`"
              :data-testid="`global-cond-row-${i}`"
              class="cond-row" shadow="never" :body-style="condRowBodyStyle"
            >
              <el-row :gutter="8">
                <el-col :span="1"><el-text class="cond-seq" tag="code" size="small" type="info">G{{ i }}</el-text></el-col>

                <el-col :xs="24" :sm="6" :md="3">
                  <el-tag size="small" type="danger" effect="plain">排除</el-tag>
                </el-col>

                <el-col :xs="24" :sm="10" :md="6">
                  <el-select
                    v-model="c.fieldCode"
                    size="small"
                    filterable
                    placeholder="字段"
                    :data-testid="`global-cond-field-${i}`"
                    @change="(v) => onFieldChange(c, v)"
                  >
                    <el-option v-for="f in fieldsMeta" :key="f.code" :label="f.label" :value="f.code" />
                  </el-select>
                </el-col>

                <el-col :xs="24" :sm="6" :md="4">
                  <el-select v-model="c.opCode" size="small" :data-testid="`global-cond-op-${i}`">
                    <el-option v-for="op in opsOf(c)" :key="op" :label="`${auditOpText(op)} ${op}`" :value="op" />
                  </el-select>
                </el-col>

                <el-col :xs="24" :sm="12" :md="7">
                  <template v-if="needsValue(c)">
                    <el-input-number
                      v-if="typeOf(c) === 'NUM'"
                      v-model="c.valueNum"
                      size="small"
                      :controls="false"
                      :data-testid="`global-cond-value-num-${i}`"
                    />
                    <el-select v-else-if="typeOf(c) === 'BOOL'" v-model="c.valueNum" size="small">
                      <el-option label="是" :value="1" />
                      <el-option label="否" :value="0" />
                    </el-select>
                    <el-input v-else v-model="c.valueText" size="small" :data-testid="`global-cond-value-text-${i}`" />
                  </template>
                  <span v-else class="muted">（该操作符不需要值）</span>
                </el-col>

                <el-col :span="3">
                  <el-button link type="danger" size="small" @click="editing.globalExclusions.splice(i, 1)">删除</el-button>
                </el-col>
              </el-row>
            </el-card>
            <el-button size="small" :icon="Plus" data-testid="global-cond-add" @click="addGlobalCondition">添加全局排除</el-button>
            <div class="muted cond-tip">
              全局排除随<b>本次保存</b>整组替换（后端 PolicyRequest.globalExclusions：null 表示不动，非 null 表示整套替换）
            </div>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="editor-footer">
          <!-- 影响预演：保存前 dry-run（当前队列里会命中几笔、其中几笔动作会变） -->
          <el-text v-if="impactText" class="impact" size="small" data-testid="policy-impact-preview">{{ impactText }}</el-text>
          <div class="footer-buttons">
            <el-button data-testid="policy-impact-dryrun" :loading="previewing" @click="dryRun">影响预演</el-button>
            <el-button data-testid="policy-editor-cancel" @click="editorVisible = false">取消</el-button>
            <el-button type="primary" :loading="saving" data-testid="policy-save" @click="save">保存</el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <!-- ================= 新增特征标（就地新增，不跳走） ================= -->
    <!-- 为什么在策略弹窗里就能新增：标目录里 category='特征' 可能一条都没有，
         而「命中时打标」的下拉只能从目录里选。若不给入口，运营会说
         「我知道该配什么标，但系统里没有，我只能去求开发写迁移」—— 那正是期 1 要消灭的自由文本回潮。
         中文名必填（它是队列里显示的那几个字），code 由人工给或留空自动生成（CUSTOM_<id>）。 -->
    <el-dialog v-model="tagCreatorVisible" title="新增特征标" width="min(520px, 92vw)" data-testid="feature-tag-dialog">
      <el-form label-width="80px" size="small">
        <el-form-item label="中文名" required>
          <el-input v-model="newTag.name" data-testid="feature-tag-name" placeholder="业务看到的几个字，如「客诉客户」" />
        </el-form-item>
        <el-form-item label="标码">
          <el-input v-model="newTag.code" data-testid="feature-tag-code" placeholder="留空自动生成（CUSTOM_<id>）；手填用大写字母/数字/下划线" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="newTag.description" data-testid="feature-tag-desc" placeholder="一句话：这个标什么意思、下一步该做什么" />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="footer-buttons">
          <el-button data-testid="feature-tag-cancel" @click="tagCreatorVisible = false">取消</el-button>
          <el-button type="primary" :loading="creatingTag" data-testid="feature-tag-submit" @click="submitNewTag">新增</el-button>
        </div>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  listAuditPolicies, policyBundle,
  createAuditPolicy, updateAuditPolicy, deleteAuditPolicy, moveAuditPolicy,
  previewAuditPolicy, listAuditQueue, queuePage, toConditionRequest, mapCondition,
  listFreezeReasons, listAuditTags, featureTagOptionsOf, createAuditTag
} from '../../api/audit'
import { handleSaveError } from '../../composables/useConflictHandler'
import { compareCondition, rowFieldValue, expectedText } from '../../composables/useAuditExplain'
import { AUDIT_POLICY_TYPE_TIP, auditPolicyTypeText, auditOpText } from '../../constants/status'

const route = useRoute()

const TYPES = ['AUTO', 'BLOCK', 'EXCEPTION']
const activeType = ref('AUTO')

const loading = ref(false)
const metaError = ref('')
const policies = ref([])
const globalExclusions = ref([])
const fieldsMeta = ref([])
// 后端给的全量操作符清单（OpCode 12 个）；具体某个字段能用哪些由 fields[].allowedOps 决定，
// 这份清单只作为「字段元数据缺失时」的兜底展示来源
const operators = ref([])

const policiesOf = (type) => policies.value.filter((p) => p.type === type).sort((a, b) => a.priority - b.priority || a.id - b.id)
const typeOf = (c) => (fieldsMeta.value.find((f) => f.code === c.fieldCode)?.valueType || c.valueType || 'TEXT').toUpperCase()

const conditionSummary = (c) => {
  const label = fieldsMeta.value.find((f) => f.code === c.fieldCode)?.label || c.fieldLabel || c.fieldCode
  const op = String(c.opCode || '').toUpperCase()
  const noValue = op === 'EXISTS' || op === 'NOT_EXISTS'
  return `${label} ${auditOpText(op)}${noValue ? '' : ' ' + expectedText({ ...c, valueType: typeOf(c) })}`
}

/**
 * 动作摘要：结论动作 + 并行动作（期 2 步骤 2）。
 *
 * 特征标显示**中文名**（目录里查得到就显示名字、查不到退化成标码，与队列那一列同口径）——
 * 只显示码的话，运营得背下 COMPLAINT_CUSTOMER 才知道这条策略在打什么标。
 */
const featureTagNames = (row) =>
  (row.featureTags || []).map((code) => featureTagOptions.value.find((t) => t.code === code)?.name || code)

const actionText = (row) => {
  const conclusion = row.type === 'AUTO' ? '自动过审（状态 7 → 8）'
    : row.type === 'BLOCK' ? '阻止（前置拦截，不推进状态）'
      : `冻结：${row.actionTag || 'FROZEN'}（${row.actionReason || row.name}）`
  const marks = featureTagNames(row)
  // 打标是并行动作，与结论并列显示（不是"顺便"）：它决定下一轮别的策略读不读得到标
  return marks.length ? `${conclusion} ＋ 打标：${marks.join('、')}` : conclusion
}

/** 高亮定位：从队列「判定依据」跳过来时会带 focusPolicy / focusSeq */
const focusPolicy = computed(() => Number(route.query.focusPolicy) || null)
const focusSeq = computed(() => (route.query.focusSeq === undefined ? null : Number(route.query.focusSeq)))
const isFocused = (policyId, seq) => focusPolicy.value === Number(policyId) && focusSeq.value !== null && Number(seq) === focusSeq.value

/**
 * 条件胶囊的三档语义色，全部是 el-tag 的 type 档位（不用一条 --el-tag-* 覆盖）：
 *   被指到（就是「未满足」那条）→ warning：这一排里最该被看见的那个；
 *   排除条件 → danger（与「排除」两个字同义）；
 *   匹配条件 → primary（默认档）。
 * 原来是自定义的"浅蓝底 + 深蓝字 / 浅红底 + 深红字"四组色对，等于在页面里重造了四档色阶。
 */
const chipType = (policyId, c) =>
  isFocused(policyId, c) ? 'warning' : (c.kind === 'EXCLUDE' ? 'danger' : 'primary')

const load = async () => {
  loading.value = true
  metaError.value = ''
  try {
    const bundle = policyBundle(await listAuditPolicies())
    policies.value = bundle.policies
    globalExclusions.value = bundle.globalExclusions
    fieldsMeta.value = bundle.fields
    operators.value = bundle.operators
    if (!fieldsMeta.value.length) metaError.value = '后端没有返回 fields（字段清单）'
  } catch (e) {
    metaError.value = e?.message || String(e)
    ElMessage.error('加载策略失败：' + (e?.message || e))
  } finally {
    loading.value = false
  }
}

// ---------------------------------------------------------------- 编辑器

const editorVisible = ref(false)
const saving = ref(false)
const previewing = ref(false)
const impactText = ref('')

/* 条件行的内距：走 Card 的 body-style（官方 Attributes），不用 :deep 撬 .el-card__body */
const condRowBodyStyle = { padding: '6px 8px' }

const editing = reactive({
  id: null,
  type: 'AUTO',
  name: '',
  priority: 100,
  enabled: true,
  actionTag: 'FROZEN',
  actionReason: '',
  actionReasonCode: '',
  featureTags: [],
  conditions: [],
  globalExclusions: []
})

/** 「命中时打标」的候选（标目录 category='特征' 且启用） */
const featureTagOptions = ref([])

/**
 * 特征标候选：目录是唯一来源（前端不许抄一份标码常量表 —— 抄一份的下场是
 * "后端有 COMPLAINT_CUSTOMER、前端没有"，页面显示成空白英文码且不报错）。
 * 取失败不阻塞保存（老策略/无标场景下依旧能改条件），但下拉会是空的，所以给一句提示。
 */
const loadFeatureTagOptions = async () => {
  try {
    featureTagOptions.value = featureTagOptionsOf(await listAuditTags())
  } catch {
    featureTagOptions.value = []
  }
}

// ---------------------------------------------------------------- 就地新增特征标

const tagCreatorVisible = ref(false)
const creatingTag = ref(false)
const newTag = reactive({ code: '', name: '', description: '' })

const openTagCreator = () => {
  newTag.code = ''
  newTag.name = ''
  newTag.description = ''
  tagCreatorVisible.value = true
}

/**
 * 新增一条「特征」标并把它选进当前策略。
 *
 * ⚠️ 中文名必填：只给码就等于让目录里多出一条"没人看得懂"的项 —— 而标的中文名正是
 * 队列/抽屉里给人看的那几个字（期 1 的教训：reason="1231" 就是这么来的）。
 */
const submitNewTag = async () => {
  if (!newTag.name.trim()) {
    ElMessage.warning('请填写中文名（它就是业务人员在队列里看到的那几个字）')
    return
  }
  creatingTag.value = true
  try {
    const created = await createAuditTag({
      code: newTag.code.trim() || undefined,
      name: newTag.name.trim(),
      description: newTag.description.trim() || undefined,
      category: '特征'
    })
    await loadFeatureTagOptions()
    const code = String(created?.code || '')
    if (code && !editing.featureTags.includes(code)) editing.featureTags.push(code)
    tagCreatorVisible.value = false
    ElMessage.success(`已新增特征标「${created?.name || newTag.name}」并选上`)
  } catch (e) {
    ElMessage.error('新增特征标失败：' + (e?.message || e))
  } finally {
    creatingTag.value = false
  }
}

const blankCondition = () => ({
  kind: 'MATCH',
  scope: 'POLICY',
  fieldCode: '',
  fieldLabel: '',
  opCode: 'EQ',
  valueType: 'TEXT',
  valueNum: 0,
  valueText: ''
})

const freezeReasonOptions = ref([])
const freezeReasonNameOf = (code) => freezeReasonOptions.value.find((r) => r.code === code)?.name || ''

/**
 * 冻结原因字典：策略页只在「异常」页签用得到，但也就 11 行，
 * 打开页面就取一次最省事（失败不阻塞：下拉空着，后端仍接受只有人话的老配置）。
 */
const loadFreezeReasons = async () => {
  try {
    freezeReasonOptions.value = (await listFreezeReasons()) || []
  } catch {
    freezeReasonOptions.value = []
  }
}

const openEditor = (policy) => {
  impactText.value = ''
  if (policy) {
    editing.id = policy.id
    editing.type = policy.type
    editing.name = policy.name
    editing.priority = policy.priority
    editing.enabled = policy.enabled
    editing.actionTag = policy.actionTag || 'FROZEN'
    editing.actionReason = policy.actionReason || ''
    // 老策略的 action_json 里可能只有一句人话、没有 reason_code：
    // 按中文名反查回填，让下拉显示"它现在是哪一条"，而不是空着诱导人误以为没配
    editing.actionReasonCode = policy.actionReasonCode
      || (freezeReasonOptions.value.find((r) => r.name === policy.actionReason)?.code ?? '')
    // 特征标回填的是**码**；目录里已删/停用的码也照样回填（否则一次编辑就会把它抹掉，
    // 而策略里那条引用还写着它 —— 保存时才会被后端拒，报错位置离原因很远）
    editing.featureTags = [...(policy.featureTags || [])]
    // seq 重排成 0..n：后端保存时也是按数组下标重排的，两边口径一致（why-miss-${id}-${seq} 才对得上）
    editing.conditions = policy.conditions.map((c, i) => ({ ...mapCondition(c), seq: i }))
    editing.globalExclusions = globalExclusions.value.map((c, i) => ({ ...c, seq: i }))
  } else {
    editing.id = null
    editing.type = activeType.value
    editing.name = ''
    editing.priority = 100
    editing.enabled = true
    editing.actionTag = 'FROZEN'
    editing.actionReason = ''
    editing.actionReasonCode = ''
    editing.featureTags = []
    editing.conditions = [blankCondition()]
    editing.globalExclusions = globalExclusions.value.map((c, i) => ({ ...c, seq: i }))
  }
  editorVisible.value = true
}

const addCondition = () => editing.conditions.push(blankCondition())
const removeCondition = (i) => editing.conditions.splice(i, 1)
const addGlobalCondition = () =>
  editing.globalExclusions.push({ ...blankCondition(), kind: 'EXCLUDE', scope: 'ALL_AUTO' })

const opsOf = (c) => {
  const f = fieldsMeta.value.find((x) => x.code === c.fieldCode)
  const allowed = f?.allowedOps?.length ? f.allowedOps : []
  // 还没选字段时只给最安全的两个（EQ/NE）：NUM 与 TEXT 都支持，不会让人先配出非法组合
  return allowed.length ? allowed : ['EQ', 'NE']
}

const needsValue = (c) => {
  const op = String(c.opCode || '').toUpperCase()
  return op !== 'EXISTS' && op !== 'NOT_EXISTS'
}

/** 换字段：值类型、可选操作符、值控件全都要跟着换（旧值一律清掉，避免「毛利 含 100」这种脏组合） */
const onFieldChange = (c, code) => {
  const f = fieldsMeta.value.find((x) => x.code === code)
  c.fieldCode = code
  c.fieldLabel = f?.label || code
  c.valueType = f?.valueType || 'TEXT'
  const allowed = f?.allowedOps?.length ? f.allowedOps : ['EQ', 'NE']
  if (!allowed.includes(c.opCode)) c.opCode = allowed[0]
  c.valueNum = 0
  c.valueText = ''
}

const payloadOf = () => ({
  type: editing.type,
  priority: Number(editing.priority) || 100,
  name: (editing.name || '').trim(),
  enabled: Boolean(editing.enabled),
  actionTag: editing.type === 'EXCEPTION' ? editing.actionTag || 'FROZEN' : null,
  // actionReason 仍然提交：它是**人话**（进审计摘要与 tag.reason 快照）；
  // 选了字典项就以字典中文名为准，没选则保留原文本（老策略回退时不至于把原因清空）
  actionReason: editing.type === 'EXCEPTION'
    ? (freezeReasonNameOf(editing.actionReasonCode) || editing.actionReason || editing.name)
    : null,
  actionReasonCode: editing.type === 'EXCEPTION' ? (editing.actionReasonCode || null) : null,
  // 并行动作：三种类型都能配（后端只对「结论动作」限 EXCEPTION）
  featureTags: [...editing.featureTags],
  conditions: editing.conditions.map((c) => toConditionRequest(c, { kind: c.kind, scope: 'POLICY' })),
  // null = 不动全局排除（改策略 B 不该顺手抹掉策略 A 里配的全局排除）
  globalExclusions: editing.type === 'AUTO'
    ? editing.globalExclusions.map((c) => toConditionRequest(c, { kind: 'EXCLUDE', scope: 'ALL_AUTO' }))
    : null
})

/** 提交前的本地校验：与后端 insertCondition 的校验口径一致，少一次「保存才知道配错」 */
const validate = () => {
  if (!editing.name.trim()) return '请填写策略名称'
  // 上限与后端 MAX_FEATURE_TAGS 一致（5）：本地先拦，省一次「保存才知道」的往返
  if (editing.featureTags.length > 5) return '一次命中最多打 5 个特征标（挂太多就没人看了）'
  if (!editing.conditions.length) return '至少配一条匹配条件'
  for (const [i, c] of editing.conditions.entries()) {
    if (!c.fieldCode) return `第 ${i} 条条件还没选字段`
    if (!c.opCode) return `第 ${i} 条条件还没选操作符`
    if (needsValue(c)) {
      const t = typeOf(c)
      if (t === 'TEXT' && !String(c.valueText ?? '').trim()) return `第 ${i} 条条件还缺文本值`
      // LIST（多值字段，如「当前标记」）：必须给出一个标码，空值会被后端拒（HAS 判的是"这一个元素"）
      if (t === 'LIST' && !String(c.valueText ?? '').trim()) return `第 ${i} 条条件还缺标码`
      if (t === 'NUM' && (c.valueNum === null || c.valueNum === undefined || c.valueNum === '')) return `第 ${i} 条条件还缺数值`
    }
  }
  return ''
}

// ---------------------------------------------------------------- 影响预演

const OUTCOME = { AUTO: 'AUTO_PASS', BLOCK: 'BLOCKED', EXCEPTION: 'FROZEN' }

/**
 * 影响预演：保存前 dry-run —— 「当前队列 N 笔中，这条策略会命中 M 笔，其中 K 笔动作变化」。
 *
 * 数据来源两级：
 *  ① 后端若提供 POST /admin/audit-policies/preview（dry-run，不落库）→ 用它的精确值；
 *  ② 后端暂无该端点 → 本地用**当前队列里看得到的字段**对一遍，并在文案里明说是估算、
 *     有多少笔因为字段没回传而无法判定。绝不把估算值伪装成精确值。
 */
const computeImpact = async () => {
  const payload = payloadOf()
  try {
    const r = (await previewAuditPolicy(payload)) || {}
    return {
      queueTotal: Number(r.queueTotal ?? r.total ?? 0),
      hitCount: Number(r.hitCount ?? r.hit ?? 0),
      actionChanged: Number(r.actionChanged ?? r.changed ?? 0),
      unknown: 0,
      mode: 'BACKEND'
    }
  } catch {
    // 端点不存在 / 网络失败 → 本地估算
  }
  const page = queuePage(await listAuditQueue({ page: 1, size: 50 }))
  const conds = editing.conditions.map((c) => ({ ...c, valueType: typeOf(c) }))
  let hit = 0
  let changed = 0
  let unknown = 0
  for (const row of page.rows) {
    let allKnown = true
    let allSatisfied = true
    for (const c of conds) {
      const { known, value } = rowFieldValue(row, c.fieldCode)
      if (!known) {
        allKnown = false
        break
      }
      if (compareCondition(c, value, known) !== true) {
        allSatisfied = false
        break
      }
    }
    if (!allKnown) {
      unknown++
      continue
    }
    if (!allSatisfied) continue
    hit++
    if (String(row.result).toUpperCase() !== OUTCOME[editing.type]) changed++
  }
  return {
    queueTotal: page.total,
    hitCount: hit,
    actionChanged: changed,
    unknown,
    mode: 'LOCAL'
  }
}

const impactMessage = (r) =>
  `当前队列 ${r.queueTotal} 笔中，这条策略会命中 ${r.hitCount} 笔，其中 ${r.actionChanged} 笔动作变化`
  + (r.mode === 'BACKEND'
    ? '（后端 dry-run，未落库）'
    : `（本地估算：只用队列可见字段，另有 ${r.unknown} 笔因字段未回传无法判定；后端 /preview 上线后即为精确值）`)

const dryRun = async () => {
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  previewing.value = true
  try {
    const r = await computeImpact()
    impactText.value = impactMessage(r)
    ElMessage.info(impactText.value)
  } catch (e) {
    ElMessage.error('影响预演失败：' + (e?.message || e))
  } finally {
    previewing.value = false
  }
}

// ---------------------------------------------------------------- 保存

const save = async () => {
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  const payload = payloadOf()
  // 保存前先预演一次：让「会不会改到一大批单」这件事在点保存之前就看见
  let impact = null
  try {
    impact = await computeImpact()
    impactText.value = impactMessage(impact)
  } catch {
    impact = null
  }
  try {
    await ElMessageBox.confirm(
      impact
        ? `${impactText.value}\n\n保存后会立刻影响下一轮自动审核，是否继续？`
        : '预演暂时拿不到数据（队列读取失败），仍要保存吗？',
      editing.id ? '确认保存策略修改' : '确认新建策略',
      { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '再看看' }
    )
  } catch {
    return
  }
  saving.value = true
  try {
    if (editing.id) {
      await updateAuditPolicy(editing.id, payload)
      ElMessage.success('策略已保存（版本号 +1）')
    } else {
      await createAuditPolicy(payload)
      ElMessage.success('策略已新建')
    }
    editorVisible.value = false
    load()
  } catch (e) {
    // 409 版本冲突 / 400 缺版本号交给统一冲突弹窗（会提示刷新后重填）
    if (handleSaveError(e, { onRefresh: load }) !== 'other') return
    ElMessage.error('保存失败：' + (e?.message || e))
  } finally {
    saving.value = false
  }
}

// ---------------------------------------------------------------- 行操作

const move = async (row, direction) => {
  try {
    await moveAuditPolicy(row.id, direction)
    ElMessage.success(`已${direction === 'UP' ? '上移' : '下移'}「${row.name}」`)
    load()
  } catch (e) {
    ElMessage.error(`${direction === 'UP' ? '上移' : '下移'}失败：` + (e?.message || e))
  }
}

/** 启用/停用：把整条策略按当前内容提交（后端 PUT 是整体覆盖 + 版本 +1） */
const toggleEnabled = async (row, value) => {
  try {
    await updateAuditPolicy(row.id, {
      type: row.type,
      priority: row.priority,
      name: row.name,
      enabled: Boolean(value),
      actionTag: row.actionTag,
      actionReason: row.actionReason,
      // ⚠️ PUT 是**整体覆盖**：漏传 featureTags 就等于"切个启用开关顺手把它清空"（静默丢配置）
      featureTags: [...(row.featureTags || [])],
      conditions: row.conditions.map((c) => toConditionRequest(c, { kind: c.kind, scope: 'POLICY' })),
      globalExclusions: null // 不动全局排除
    })
    ElMessage.success(`已${value ? '启用' : '停用'}「${row.name}」`)
    load()
  } catch (e) {
    if (handleSaveError(e, { onRefresh: load }) !== 'other') return
    ElMessage.error('切换启用状态失败：' + (e?.message || e))
    load()
  }
}

const remove = async (row) => {
  try {
    await ElMessageBox.confirm(
      `删除后这条策略立即不再参与判定（历史决策仍保留策略名与版本号的快照）。确认删除「${row.name}」？`,
      '删除策略',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteAuditPolicy(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error('删除失败：' + (e?.message || e))
  }
}

onMounted(async () => {
  if (route.query.type && TYPES.includes(String(route.query.type).toUpperCase())) {
    activeType.value = String(route.query.type).toUpperCase()
  }
  await load()
  await loadFreezeReasons()
  await loadFeatureTagOptions()
  // 从队列「判定依据 → 去改这条策略」跳过来：直接打开对应策略的编辑器，条件高亮由 isFocused 负责
  if (focusPolicy.value) {
    const target = policies.value.find((p) => p.id === focusPolicy.value)
    if (target) {
      activeType.value = target.type
      openEditor(target)
    }
  }
})
</script>

<style scoped>
/* 与全局 .header-tip 只差一个左边距：字号/字色已在 main.css */
.card-header .header-tip { margin-left: 10px; }
.header-actions { display: flex; gap: 8px; }
.tab-tip { margin-bottom: 8px; }
/* 跨类型优先级说明 = el-alert(info)：浅底 + 图标；原来那套"12px 灰字 + 左侧 3px 竖线 + 2px 圆角"
   是自己拼的引注块，删掉不丢信息（图标在这里起的作用比竖线强）。 */
.priority-note { margin: 4px 0 10px; }

.cond-chips { display: flex; flex-wrap: wrap; gap: 6px; }
/* 条件胶囊 = el-tag：三档语义色走 type，深浅走 effect；这里只留"标签之间对齐"的间距 */
.cond-chip { gap: 4px; }

/* 全局排除卡：就是一张普通 el-card（原来钉了一个 #fcfcfd 的近白底，与白底肉眼无差，纯追旧值） */
.global-card { margin-top: 14px; }
.global-head { display: flex; align-items: baseline; gap: 10px; }

/* el-row 的 gutter 会在左右各拉出 -(gutter/2) 的负外边距，容器没有内边距时会溢出
   （弹窗里表现为一条 6px 的横向滚动条）。给这些行套一层带 padding 的壳吸收掉。 */
.form-row { padding: 0 6px; }

.cond-editor { width: 100%; }
/* 条件行只负责"把这个条件圈成一组"的外观；布局（display/flex-wrap）由内层 el-row 给，
   列宽由 el-col 的 :span 给。gutter 的负外边距被这里的 padding 吃掉，不会外溢弹窗。 */
/* 条件行 = el-card：底色/边框/圆角全部组件默认（4px），内距走 :body-style。
   原来钉了 fill-lighter 底 + border-lighter 边 + 4px 圆角 —— 三条都是"追旧值"，与全站每张卡同一个样子。 */
.cond-row { margin-bottom: 6px; }
/* 字色/字号走 el-text 的 type=info + size=small；等宽由 tag="code" 提供（UA 的 <code> 自带 monospace），
   这里只剩居中：列宽只有 1/24，序号要在列里居中才不会看起来像"飘在左边"。 */
.cond-seq {
  display: block; text-align: center;
}
.cond-tip { margin-top: 6px; }
/* 「命中时打标」= 多选下拉 + 就地新增按钮：下拉铺满、按钮不换行挤成两行 */
.feature-tag-picker { display: flex; gap: 8px; width: 100%; align-items: center; }
.editor-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.editor-footer .impact { text-align: left; flex: 1; }
.footer-buttons { display: flex; gap: 8px; }
</style>
