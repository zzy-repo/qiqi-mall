<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>令牌管理</span>
        <el-button type="primary" :icon="Plus" data-testid="api-key-create" @click="openCreate()">新增令牌</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border data-testid="api-key-list-table">
      <el-table-column prop="name" label="名称" min-width="150" />

      <!-- 只显示前缀，永远不展示完整 key -->
      <el-table-column label="Key" min-width="150">
        <template #default="{ row }">
          <span class="key-prefix">{{ row.keyPrefix ? `${row.keyPrefix}…` : '—' }}</span>
        </template>
      </el-table-column>

      <el-table-column label="权限" min-width="260">
        <template #default="{ row }">
          <template v-if="scopesOf(row).length">
            <el-tag
              v-for="scope in scopesOf(row).slice(0, SCOPE_FOLD_LIMIT)"
              :key="scope"
              size="small"
              type="info"
              class="scope-tag"
            >{{ scopeLabel(scope) }}</el-tag>
            <el-tooltip
              v-if="scopesOf(row).length > SCOPE_FOLD_LIMIT"
              placement="top"
              :content="scopesOf(row).slice(SCOPE_FOLD_LIMIT).map(scopeLabel).join('、')"
            >
              <el-tag size="small" class="scope-tag scope-tag-more" data-testid="api-key-row-scopes-more">
                +{{ scopesOf(row).length - SCOPE_FOLD_LIMIT }}
              </el-tag>
            </el-tooltip>
          </template>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>

      <el-table-column label="过期时间" min-width="180">
        <template #default="{ row }">
          <template v-if="row.expiresAt">
            <el-tag v-if="isExpired(row)" type="danger" size="small" data-testid="api-key-row-expired">已过期</el-tag>
            <span class="datetime" :class="{ 'datetime-expired': isExpired(row) }">{{ formatDateTime(row.expiresAt) }}</span>
          </template>
          <span v-else class="muted">永不过期</span>
        </template>
      </el-table-column>

      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-switch
            :model-value="isEnabled(row.status)"
            :loading="!!row._toggling"
            inline-prompt
            active-text="启用"
            inactive-text="禁用"
            data-testid="api-key-row-status"
            @change="(val) => toggleStatus(row, val)"
          />
        </template>
      </el-table-column>

      <el-table-column label="最后使用" min-width="170">
        <template #default="{ row }">
          <span v-if="row.lastUsedAt" class="datetime">{{ formatDateTime(row.lastUsedAt) }}</span>
          <span v-else class="muted">从未使用</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <span :data-testid="`api-key-row-${row.id}`">
            <el-button link type="primary" size="small" data-testid="api-key-row-edit" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" data-testid="api-key-row-delete" @click="remove(row)">删除</el-button>
          </span>
        </template>
      </el-table-column>
    </el-table>

    <div class="tip">
      Key 仅在创建时明文显示一次，关闭弹窗后无法再次查看，列表里只能看到前缀；如需更换请新建一条并删除旧的。
    </div>

    <!-- 新增 / 编辑弹窗 -->
    <el-dialog
      v-model="visible"
      :title="form.id ? '编辑令牌' : '新增令牌'"
      width="560px"
      :close-on-click-modal="false"
      data-testid="api-key-form-dialog"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="50" placeholder="如：AI 助手（只读）" data-testid="api-key-form-name" />
        </el-form-item>

        <!-- 编辑时只能看前缀，不能改 key -->
        <el-form-item v-if="form.id" label="Key">
          <span class="key-prefix">{{ form.keyPrefix ? `${form.keyPrefix}…` : '—' }}</span>
          <span class="muted key-hint">Key 本身不可修改</span>
        </el-form-item>

        <!-- 权限：目录在运行时从 GET /admin/api-keys/scopes 读（前端不留抄本），按读 / 写 / 不可授予分组渲染 -->
        <el-form-item label="权限">
          <div data-testid="api-key-form-scopes" class="scope-groups">
            <!-- 目录没拉到时只能报错：绝不回落到一份写死的前端清单 -->
            <el-alert
              v-if="catalogError"
              type="error"
              :closable="false"
              show-icon
              class="scope-error"
              data-testid="api-key-form-scopes-error"
              title="权限目录加载失败，暂时无法保存"
              :description="`${catalogError}（目录只从后端读，不用本地兜底；请点「重试」或在网络恢复后重新打开本页）`"
            />
            <el-button
              v-if="catalogError"
              size="small"
              :loading="catalogLoading"
              data-testid="api-key-form-scopes-retry"
              @click="loadScopeCatalog"
            >重试</el-button>
            <div v-else-if="catalogLoading" class="scope-loading" data-testid="api-key-form-scopes-loading">正在读取权限目录…</div>

            <div
              v-for="group in scopeGroups"
              :key="group.key"
              class="scope-group-block"
              :data-testid="`api-key-form-scope-group-${group.key}`"
            >
              <div class="scope-group-title">
                <span class="scope-group-name">{{ group.title }}</span>
                <span class="scope-group-count">{{ group.scopes.length }}</span>
              </div>
              <div class="scope-group-hint">{{ group.hint }}</div>

              <el-checkbox-group v-if="group.grantable" v-model="form.scopes" class="scope-group">
                <el-row>
                  <el-col v-for="opt in group.scopes" :key="opt.value" :span="8">
                    <el-checkbox
                      :value="opt.value"
                      class="scope-cb"
                      :data-testid="`api-key-form-scope-${opt.value}`"
                    >{{ opt.label }}</el-checkbox>
                  </el-col>
                </el-row>
              </el-checkbox-group>

              <!-- 不可授予：只展示、永远勾不上（后端 ALL_SCOPES 里根本没有这些值）。
                   置灰/禁用光标都由 el-checkbox 的 :disabled 自带，不再用 :deep 重画内部 label。 -->
              <el-row v-else class="scope-group">
                <el-col v-for="opt in group.scopes" :key="opt.value" :span="8">
                  <el-checkbox
                    :disabled="true"
                    :model-value="false"
                    class="scope-cb"
                    :data-testid="`api-key-form-scope-denied-${opt.value}`"
                  >{{ opt.label }}</el-checkbox>
                </el-col>
              </el-row>
            </div>
          </div>
        </el-form-item>

        <el-form-item label="过期时间">
          <div class="expires-row">
            <!-- testid 挂在外层容器上：el-date-picker 不会把透传属性落到真实 input 上 -->
            <div data-testid="api-key-form-expires">
              <el-date-picker
                v-model="form.expiresAt"
                type="datetime"
                placeholder="留空 = 永不过期"
                value-format="YYYY-MM-DD HH:mm:ss"
                :clearable="true"
                style="width: 100%"
              />
            </div>
            <div class="preset-row">
              <el-button size="small" data-testid="api-key-form-expires-7d" @click="setExpiresPreset(7)">7 天</el-button>
              <el-button size="small" data-testid="api-key-form-expires-30d" @click="setExpiresPreset(30)">30 天</el-button>
              <el-button size="small" data-testid="api-key-form-expires-never" @click="setExpiresPreset(null)">永不过期</el-button>
            </div>
          </div>
        </el-form-item>

        <el-form-item v-if="form.id" label="状态">
          <el-switch v-model="form.enabled" inline-prompt active-text="启用" inactive-text="禁用" data-testid="api-key-form-status" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="api-key-form-cancel" @click="visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="!catalogReady"
          data-testid="api-key-form-submit"
          @click="submit"
        >{{ catalogReady ? '确定' : '目录未加载' }}</el-button>
      </template>
    </el-dialog>

    <!-- 只显示一次的明文 Key 弹窗：关闭后不再展示 -->
    <el-dialog
      v-model="revealVisible"
      title="令牌创建成功"
      width="560px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="true"
      destroy-on-close
      data-testid="api-key-reveal-dialog"
      @closed="onRevealClosed"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="请立即复制保存，关闭后无法再次查看"
        description="这是唯一一次显示该 Key 的明文，之后列表里只能看到前缀。丢失后只能删除重建。"
      />
      <div class="reveal-box">
        <code class="reveal-value" data-testid="api-key-reveal-value">{{ revealKey }}</code>
        <el-button type="primary" :icon="CopyDocument" data-testid="api-key-reveal-copy" @click="copyRevealKey">复制</el-button>
      </div>
      <template #footer>
        <el-button type="primary" data-testid="api-key-reveal-close" @click="revealVisible = false">我已保存，关闭</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus, CopyDocument } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listApiKeys, createApiKey, updateApiKey, deleteApiKey } from '../../api/apiKey'
import { fetchScopeCatalog, buildScopeGroups, grantableValuesOf, scopeLabelsOf } from './scopeCatalog'

/**
 * 权限项不在这里写死，也不在前端文件里写死：全部来自 `GET /admin/api-keys/scopes`
 * （后端 ALL_SCOPES 的 13 个可授予 + ApiKeyAuthFilter 的 5 个不可授予）。
 * 这里只保留「列表里权限超过几个就折叠成 +N」这一个展示参数。
 */
const SCOPE_FOLD_LIMIT = 3

const list = ref([])
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const currentRow = ref(null)
const form = reactive({ id: null, name: '', scopes: [], expiresAt: null, enabled: true, keyPrefix: '' })
const rules = {
  name: [{ required: true, message: '请输入令牌名称', trigger: 'blur' }]
}

/* ---------------- 权限目录（运行时读，失败即禁止保存） ---------------- */

/** 接口原样回的目录：{ read, write, unGrantable, grantable }；null = 还没拉到 */
const scopeCatalog = ref(null)
const catalogLoading = ref(false)
const catalogError = ref('')
/** 只有目录到手才允许保存 —— 不许用一份前端写死的清单顶上 */
const catalogReady = computed(() => !!scopeCatalog.value && !catalogError.value)
const scopeGroups = computed(() => buildScopeGroups(scopeCatalog.value))
const grantableValues = computed(() => grantableValuesOf(scopeCatalog.value))
const labelOfScope = computed(() => scopeLabelsOf(scopeCatalog.value))

const loadScopeCatalog = async () => {
  catalogLoading.value = true
  catalogError.value = ''
  try {
    scopeCatalog.value = await fetchScopeCatalog()
  } catch (e) {
    // 关键：不 fallback。目录拿不到就明确失败，保存按钮随之禁用。
    scopeCatalog.value = null
    catalogError.value = '权限目录加载失败：' + (e.message || '未知错误')
    ElMessage.error(catalogError.value)
  } finally {
    catalogLoading.value = false
  }
}

const revealVisible = ref(false)
const revealKey = ref('')

/* ---------------- 展示辅助 ---------------- */

/** scope → 中文标签；标签也来自接口目录，目录里没有的值原样显示（后端删了 scope 时一眼看得见） */
const scopeLabel = (scope) => labelOfScope.value(scope)
const scopesOf = (row) => (Array.isArray(row.scopes) ? row.scopes : [])

/**
 * 只保留后端真能授予的 scope（= 接口 grantable 的那 13 个）。
 * 库里若残留了目录外的值（例如某天后端删掉了某个 scope），编辑时不会把它悄悄带回请求体里
 * —— 后端对未知 scope 直接回「未知权限：…」，那样一次正常保存都会失败。
 */
const grantableOnly = (scopes) => scopes.filter((scope) => grantableValues.value.includes(scope))

/**
 * status 兼容：后端契约只写了 status，未固定字面量。
 * 这里同时支持 1/0、true/false、以及 'ENABLED'/'DISABLED'、'ACTIVE'/'INACTIVE' 之类的字符串。
 */
const isEnabled = (status) => {
  if (status === null || status === undefined || status === '') return true // 缺省视为启用
  if (typeof status === 'number') return status === 1
  if (typeof status === 'boolean') return status
  const s = String(status).toLowerCase()
  return ['1', 'true', 'enabled', 'enable', 'active', 'normal', 'ok'].includes(s)
}

/** 把「启用/禁用」转成与后端返回同类型的状态值，避免类型不一致导致后端反序列化失败 */
const statusOf = (raw, enabled) => {
  if (typeof raw === 'number') return enabled ? 1 : 0
  if (typeof raw === 'boolean') return enabled
  const s = String(raw || '').toUpperCase()
  if (s === 'ACTIVE' || s === 'INACTIVE') return enabled ? 'ACTIVE' : 'INACTIVE'
  if (s === 'NORMAL' || s === 'DISABLE') return enabled ? 'NORMAL' : 'DISABLE'
  return enabled ? 'ENABLED' : 'DISABLED'
}

const pad = (n) => String(n).padStart(2, '0')

/** 兼容后端可能返回的几种时间形态：'2026-01-01 10:00:00' / ISO 串 / [y,m,d,h,mi,s] / 时间戳 */
const toTimestamp = (value) => {
  if (value === null || value === undefined || value === '') return null
  if (Array.isArray(value)) {
    const [y, mo = 1, d = 1, h = 0, mi = 0, s = 0] = value
    const t = new Date(y, Number(mo) - 1, d, h, mi, s).getTime()
    return Number.isNaN(t) ? null : t
  }
  if (typeof value === 'number') return value
  const t = Date.parse(String(value).replace(' ', 'T'))
  return Number.isNaN(t) ? null : t
}

const formatDateTime = (value) => {
  const t = toTimestamp(value)
  if (t === null) return ''
  const d = new Date(t)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

const isExpired = (row) => {
  const t = toTimestamp(row.expiresAt)
  return t !== null && t < Date.now()
}

/* ---------------- 列表 ---------------- */

const load = async () => {
  loading.value = true
  try {
    const data = await listApiKeys()
    list.value = Array.isArray(data) ? data : (data?.list ?? [])
  } catch (e) {
    list.value = []
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

/* ---------------- 新增 / 编辑 ---------------- */

const openCreate = () => {
  currentRow.value = null
  Object.assign(form, { id: null, name: '', scopes: [], expiresAt: null, enabled: true, keyPrefix: '' })
  visible.value = true
  formRef.value?.clearValidate()
}

const openEdit = (row) => {
  currentRow.value = row
  Object.assign(form, {
    id: row.id,
    name: row.name || '',
    scopes: grantableOnly(scopesOf(row)),
    expiresAt: row.expiresAt ? formatDateTime(row.expiresAt) : null,
    enabled: isEnabled(row.status),
    keyPrefix: row.keyPrefix || ''
  })
  visible.value = true
  formRef.value?.clearValidate()
}

/** 快捷过期时间：days=null 表示永不过期 */
const setExpiresPreset = (days) => {
  if (days === null) {
    form.expiresAt = null
    return
  }
  form.expiresAt = formatDateTime(Date.now() + days * 24 * 60 * 60 * 1000)
}

const submit = async () => {
  // 目录没到手就没有「哪些能勾」的事实依据：直接拒，绝不用前端写死的清单兜底（按钮也已禁用）
  if (!catalogReady.value) {
    ElMessage.error(catalogError.value || '权限目录尚未加载，无法保存')
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 后端 normalizeScopes 对空数组回「权限不能为空」，先在页面上拦一句更直白的话
  const scopes = grantableOnly(form.scopes)
  if (!scopes.length) {
    ElMessage.warning('请至少勾选一个权限')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      scopes,                            // 已按可授予白名单过滤（顺序 = 页面勾选顺序）
      expiresAt: form.expiresAt || null // 留空 = 永不过期
    }
    if (form.id) {
      payload.status = statusOf(currentRow.value?.status, form.enabled)
      await updateApiKey(form.id, payload)
      ElMessage.success('保存成功')
      visible.value = false
      await load()
    } else {
      const created = await createApiKey(payload)
      visible.value = false
      await load()
      // 明文 key 只在创建响应里出现这一次
      if (created?.key) {
        revealKey.value = created.key
        revealVisible.value = true
      } else {
        ElMessage.success('创建成功')
      }
    }
  } catch (e) {
    ElMessage.error('保存失败：' + e.message)
  } finally {
    saving.value = false
  }
}

const toggleStatus = async (row, val) => {
  const prev = row.status
  const next = statusOf(prev, val)
  row.status = next // 乐观更新，失败回滚
  row._toggling = true
  try {
    await updateApiKey(row.id, { status: next })
    ElMessage.success(val ? '已启用' : '已禁用')
  } catch (e) {
    row.status = prev
    ElMessage.error('状态更新失败：' + e.message)
  } finally {
    row._toggling = false
  }
}

const remove = async (row) => {
  const ok = await ElMessageBox.confirm(
    `确认删除令牌「${row.name}」？删除后该 Key 立即失效，使用它的 AI 将无法调用接口。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteApiKey(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error('删除失败：' + e.message)
  }
}

/* ---------------- 一次性明文展示 ---------------- */

/** 关闭弹窗立刻丢弃明文，之后无法再从任何地方取回 */
const onRevealClosed = () => {
  revealKey.value = ''
}

/** 老浏览器兜底：navigator.clipboard 需要 https/localhost 才可用 */
const legacyCopy = (text) => {
  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.setAttribute('readonly', '')
    textarea.style.position = 'fixed'
    textarea.style.top = '-1000px'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  } catch {
    return false
  }
}

const copyRevealKey = async () => {
  const text = revealKey.value
  if (!text) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      ElMessage.success('已复制到剪贴板')
      return
    }
    throw new Error('clipboard unavailable')
  } catch {
    if (legacyCopy(text)) ElMessage.success('已复制到剪贴板')
    else ElMessage.error('复制失败，请手动选中后复制')
  }
}

onMounted(() => {
  load()
  // 目录与列表并行拉：任一失败都只影响自己那块（目录失败 ⇒ 保存禁用）
  loadScopeCatalog()
})
</script>

<style scoped>
.tip { margin-top: 10px; font-size: 12px; color: #909399; }
.key-prefix { font-family: 'SFMono-Regular', Consolas, Monaco, monospace; font-size: 13px; }
.key-hint { margin-left: 8px; }
.scope-tag { margin: 2px 6px 2px 0; }
.scope-tag-more { cursor: default; }
.scope-groups { width: 100%; }
.scope-error { margin-bottom: 8px; }
.scope-loading { font-size: 13px; color: #909399; line-height: 22px; }
.scope-group-block { margin-bottom: 10px; }
.scope-group-block:last-child { margin-bottom: 0; }
.scope-group-title { display: flex; align-items: center; gap: 6px; line-height: 20px; }
.scope-group-name { font-size: 13px; font-weight: 600; color: #303133; }
.scope-group-count {
  min-width: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: #f0f2f5;
  color: #909399;
  font-size: 12px;
  text-align: center;
}
.scope-group-hint { font-size: 12px; color: #909399; line-height: 18px; margin: 2px 0 4px; }
/* 一行三个由 <el-col :span="8"> 决定；这里只把 el-checkbox 默认的 30px 右外边距归零，
   并让它铺满列宽。类挂在 el-checkbox 的根元素上，不是 :deep 撬内部。 */
.scope-group { width: 100%; }
.scope-cb { width: 100%; margin-right: 0; }
.datetime-expired { color: #f56c6c; margin-left: 6px; }
.expires-row { width: 100%; }
.preset-row { margin-top: 6px; display: flex; gap: 8px; }
.reveal-box {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  background: #f5f7fa;
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
}
.reveal-value {
  flex: 1;
  font-family: 'SFMono-Regular', Consolas, Monaco, monospace;
  font-size: 13px;
  color: #303133;
  word-break: break-all;
  user-select: all;
}
</style>
