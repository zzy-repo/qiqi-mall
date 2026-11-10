<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="brand">
        <el-icon :size="24"><Shop /></el-icon>
        <span>七七商城</span>
      </div>

      <el-tabs v-model="tab" stretch>
        <el-tab-pane label="登录" name="login" />
        <el-tab-pane label="注册" name="register" />
      </el-tabs>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" size="large" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" size="large" show-password placeholder="请输入密码" />
        </el-form-item>
        <template v-if="tab === 'register'">
          <el-form-item label="昵称" prop="nickname">
            <el-input v-model="form.nickname" size="large" placeholder="选填，默认与用户名相同" />
          </el-form-item>
          <el-form-item label="手机号" prop="phone">
            <el-input v-model="form.phone" size="large" placeholder="选填" />
          </el-form-item>
        </template>

        <el-alert v-if="errorText" :title="errorText" type="error" :closable="false" show-icon class="error" />

        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
          {{ tab === 'login' ? '登录' : '注册并登录' }}
        </el-button>
        <div class="tip">
          {{ tab === 'login' ? '还没有账号？点上方「注册」' : '注册成功后将自动登录' }}
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, register } from '../../api/auth'
import { mergeLocalCart } from '../../api/cart'
import { setToken, setUser } from '../../utils/auth'
import { toMergePayload, clearLocalCart } from '../../utils/localCart'

const route = useRoute()
const router = useRouter()

const tab = ref('login')
const loading = ref(false)
const errorText = ref('')
const formRef = ref()
const form = reactive({ username: '', password: '', nickname: '', phone: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

watch(tab, () => { errorText.value = '' })

/** 登录/注册成功后：合并本地购物车 → 回跳 redirect */
const afterAuth = async (data) => {
  setToken(data.accessToken)
  setUser({ memberId: data.memberId, username: data.username, nickname: data.nickname })

  const payload = toMergePayload()
  if (payload.length) {
    try {
      const merged = await mergeLocalCart(payload)
      clearLocalCart()
      if (merged > 0) ElMessage.success(`已合并 ${merged} 件本地购物车商品`)
    } catch {
      // 合并失败不阻断登录
    }
  }

  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  router.replace(redirect)
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  errorText.value = ''
  loading.value = true
  try {
    const data = tab.value === 'login'
      ? await login({ username: form.username, password: form.password })
      : await register({ username: form.username, password: form.password, nickname: form.nickname, phone: form.phone })
    await afterAuth(data)
  } catch (e) {
    errorText.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #eef4ff 0%, #f7faff 100%);
}
.login-card { width: 400px; padding: 8px 12px; }
.brand {
  display: flex; align-items: center; gap: 8px; justify-content: center;
  font-size: 18px; font-weight: 600; color: #1f2d3d; margin-bottom: 12px;
}
.submit { width: 100%; margin-top: 8px; }
.error { margin-bottom: 12px; }
.tip { margin-top: 12px; font-size: 12px; color: #909399; text-align: center; }
</style>
