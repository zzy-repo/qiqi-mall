<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>会员列表</span>
        <el-button type="primary" :icon="Plus" data-testid="member-list-create" @click="openForm()">新增</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border data-testid="member-list-table">
      <el-table-column prop="username" label="登录名" min-width="140" />
      <el-table-column prop="nickname" label="昵称" width="120" />
      <el-table-column prop="phone" label="手机号" width="140" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status ? 'success' : 'info'" size="small">{{ row.status ? '启用' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="注册时间" width="180" />
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" data-testid="member-row-addresses" @click="openAddresses(row)">收货地址</el-button>
          <el-button link type="primary" size="small" data-testid="member-row-carts" @click="openCarts(row)">购物车</el-button>
          <el-button link type="primary" size="small" data-testid="member-row-edit" @click="openForm(row)">编辑</el-button>
          <el-button
            link
            type="danger"
            size="small"
            :data-testid="`member-row-delete-${row.id}`"
            @click="remove(row)"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? '编辑会员' : '新增会员'" width="500px" data-testid="member-form-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="登录名" prop="username">
          <el-input v-model="form.username" data-testid="member-form-username" maxlength="50" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" data-testid="member-form-nickname" maxlength="50" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" data-testid="member-form-phone" maxlength="20" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" data-testid="member-form-status" :active-value="1" :inactive-value="0" active-text="启用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-testid="member-form-cancel" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" data-testid="member-form-submit" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listMembers, createMember, updateMember, deleteMember } from '../../api/member'
import { useDangerConfirm } from '../../composables/useDangerConfirm'

const router = useRouter()
/** 删除会员（高危）：预演 → 确认 → 执行（弹窗全站单例，见 layout/index.vue） */
const danger = useDangerConfirm()

const list = ref([])
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    list.value = await listMembers()
  } catch (e) {
    ElMessage.error('加载失败：' + e.message)
  } finally {
    loading.value = false
  }
}

const openAddresses = (row) => router.push(`/member/${row.id}/addresses`)
const openCarts = (row) => router.push(`/member/${row.id}/carts`)

const visible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: null, username: '', nickname: '', phone: '', status: 1 })

const rules = {
  username: [{ required: true, message: '请输入登录名', trigger: 'blur' }]
}

const openForm = (row) => {
  Object.assign(form, row ? { ...row } : { id: null, username: '', nickname: '', phone: '', status: 1 })
  visible.value = true
  formRef.value?.clearValidate()
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (form.id) await updateMember(form.id, form)
    else await createMember(form)
    ElMessage.success('保存成功')
    visible.value = false
    await load()
  } catch (e) {
    ElMessage.error('保存失败：' + e.message)
  } finally {
    saving.value = false
  }
}

/* ------------------------------------------------------------------ *
 * 删除会员（高危）
 *
 * 背景：后端 `DELETE /admin/members/{id}` 一直存在，MCP 的 member(action) 也早就有
 * delete —— 也就是说「AI 能删会员，人在界面上却不能」，这是反向的权限不对称
 * （人应该是这个系统的主体，至少不能比 AI 少一项能力）。这里把这个缺口补齐。
 *
 * 危险确认接法：`useDangerConfirm().run({ dryRun, exec, onSuccess, onError, ... })`，
 * 与全站另外 13 个高危操作完全同一套（弹窗渲染在 layout 里的单例 DangerConfirmDialog）。
 * 但会员删除**后端没有 dryRun 预演接口**（纯 DELETE，没有 ?dryRun=true 分支），
 * 而 run() 只要求 dryRun 是「返回 Promise<{ willDo, details? }> 的函数」，并不要求它真发请求，
 * 所以这里传一个**本地构造**的预演：用行数据 + 已知的级联/拒绝规则拼出「将要发生什么」。
 * 不硬套一个不存在的后端预演，也不因此绕开确认 —— 该说清的后果一样说清。
 * ------------------------------------------------------------------ */

/** 会员在界面上的称呼：昵称优先，其次登录名，最后退化成 id（都不会是空白） */
const memberLabel = (row) => row.nickname || row.username || `#${row.id}`

/**
 * 后端「数据层拒绝」的通用兜底文案（GlobalExceptionHandler 的 DataAccessException 分支）。
 *
 * 会员删除被拒有两条外键，都**没有**级联：`fk_order_member`（名下有订单）、
 * `fk_return_member`（名下有退货申请，即 `return_apply.member_id`）；
 * `member_address` / `cart` 的外键则是 ON DELETE CASCADE（会一并删掉），不是拒绝原因。
 * 只要还挂着订单或退货申请，DELETE 就被数据库拒绝，异常处理器回一句通用文案：
 * 「数据处理失败，请稍后重试」（DataAccessException 分支）或
 * 「数据被其它记录引用，无法删除（请先处理关联的物流、退货、明细等记录）」
 * （DataIntegrityViolationException 兜底分支，2026-11 起存在）。
 * 两句对会员这一页都等于什么都没说（后者讲的还是物流/明细，与会员无关），
 * 所以两种都要认出来，统一翻成会员自己那份原因。
 */
const DB_GENERIC_FAILURE =
  /数据处理失败|数据被其它记录引用|IntegrityConstraintViolation|Cannot delete or update a parent row|foreign key constraint/i

/** 把删除失败的原因翻成人话（非通用兜底文案原样透出，不加工） */
const humanizeDeleteError = (e) => {
  const raw = typeof e?.message === 'string' ? e.message.trim() : ''
  if (!raw) return '删除失败，请稍后重试；也可以改为禁用（status=0）'
  if (DB_GENERIC_FAILURE.test(raw)) return '该会员名下有订单或退货申请，无法删除；可改为禁用（status=0）'
  return raw
}

/** 本地构造的「预演」：没有后端 dryRun，就把这条记录上确定的信息与后果说清楚 */
const localDryRun = (row) => async () => ({
  willDo:
    `确认删除会员「${memberLabel(row)}」？该会员的收货地址与购物车会一并删除；` +
    '若其名下有订单或退货申请则会被拒绝。删除后不可恢复。'
})

/**
 * 弹窗「明细」区：把「要删的是哪一个」摊开（登录名 / 状态 / 注册时间），全部取自行数据。
 * 注意 run() 的 details 是**函数选项**（(preview) => [{label,value}]），不是 dryRun 返回值里的字段 ——
 * 早先把它塞进 dryRun 的返回对象里，弹窗明细区就直接空了。
 */
const memberDetails = (row) => () => [
  { label: '登录名', value: row.username || '（空）' },
  { label: '当前状态', value: row.status ? '启用' : '禁用' },
  { label: '注册时间', value: row.createTime || '（未知）' }
]

/**
 * 删除会员：危险确认 → 执行 → 成功则刷新列表（成功提示由 run() 按 successText 弹出）。
 *
 * onError 返回 false = **不收起弹窗**：把翻好的人话留在弹窗里（dangerState.error），
 * 用户能看着原因决定下一步（改成禁用 / 先去处理订单或退货申请），而不是被一条 2 秒就消失的 toast 打发。
 */
const remove = async (row) => {
  await danger.run({
    title: `⚠️ 确认删除会员「${memberLabel(row)}」`,
    confirmText: '确认删除',
    successText: '已删除',
    dryRun: localDryRun(row),
    details: memberDetails(row),
    exec: () => deleteMember(row.id),
    onSuccess: () => load(),
    onError: (e) => {
      const human = humanizeDeleteError(e)
      if (human !== (typeof e?.message === 'string' ? e.message.trim() : '')) e.message = human
      return false
    }
  })
}

onMounted(load)
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>
