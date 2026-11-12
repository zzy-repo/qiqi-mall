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

        <el-form-item label="权限">
          <el-checkbox-group v-model="form.scopes" data-testid="api-key-form-scopes" class="scope-group">
            <el-checkbox
              v-for="opt in SCOPE_OPTIONS"
              :key="opt.value"
              :value="opt.value"
              :data-testid="`api-key-form-scope-${opt.value}`"
            >{{ opt.label }}</el-checkbox>
          </el-checkbox-group>
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
        <el-button type="primary" :loading="saving" data-testid="api-key-form-submit" @click="submit">确定</el-button>
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
import { ref, reactive, onMounted } from 'vue'
import { Plus, CopyDocument } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listApiKeys, createApiKey, updateApiKey, deleteApiKey } from '../../api/apiKey'

/** 权限（scope）固定 9 项，前端只展示中文标签 */
const SCOPE_OPTIONS = [
  { value: 'order:read', label: '订单-读' },
  { value: 'order:write', label: '订单-写' },
  { value: 'catalog:read', label: '商品-读' },
  { value: 'catalog:write', label: '商品-写' },
  { value: 'member:read', label: '会员-读' },
  { value: 'member:write', label: '会员-写' },
  { value: 'config:read', label: '配置-读' },
  { value: 'config:write', label: '配置-写' },
  { value: 'dashboard:read', label: '概览-读' }
]
const SCOPE_LABEL = SCOPE_OPTIONS.reduce((acc, o) => ({ ...acc, [o.value]: o.label }), {})
/** 列表里权限超过这个数量就折叠成 +N */
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

const revealVisible = ref(false)
const revealKey = ref('')

/* ---------------- 展示辅助 ---------------- */

/** scope → 中文标签；未知 scope 原样显示，避免后端新增权限时前端空白 */
const scopeLabel = (scope) => SCOPE_LABEL[scope] || scope
const scopesOf = (row) => (Array.isArray(row.scopes) ? row.scopes : [])

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
    scopes: scopesOf(row).slice(),
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
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      scopes: form.scopes.slice(),
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

onMounted(load)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.tip { margin-top: 10px; font-size: 12px; color: #909399; }
.muted { color: #909399; font-size: 13px; }
.key-prefix { font-family: 'SFMono-Regular', Consolas, Monaco, monospace; font-size: 13px; }
.key-hint { margin-left: 8px; }
.scope-tag { margin: 2px 6px 2px 0; }
.scope-tag-more { cursor: default; }
.scope-group { display: flex; flex-wrap: wrap; gap: 0 4px; }
.scope-group :deep(.el-checkbox) { width: 30%; margin-right: 0; }
.datetime { font-size: 13px; color: #606266; }
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
