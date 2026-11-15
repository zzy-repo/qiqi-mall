<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <el-text class="brand" tag="div">
        <el-icon :size="26"><Shop /></el-icon>
        <span>七七商城 · 管理后台</span>
      </el-text>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" data-testid="login-form-username" placeholder="请输入用户名" size="large" @blur="checkCaptcha" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            data-testid="login-form-password"
            type="password"
            placeholder="请输入密码"
            size="large"
            show-password
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-form-item v-if="needCaptcha" label="验证码" prop="captchaCode">
          <div class="captcha-row">
            <el-input
              v-model="form.captchaCode"
              data-testid="login-form-captcha"
              placeholder="请输入验证码"
              size="large"
              maxlength="4"
            />
            <img
              v-if="captchaImage"
              :src="captchaImage"
              class="captcha-img"
              title="点击刷新"
              alt="验证码"
              @click="loadCaptcha"
            />
          </div>
        </el-form-item>

        <el-alert v-if="errorText" :title="errorText" type="error" :closable="false" show-icon class="error" />

        <el-button type="primary" size="large" class="submit" data-testid="login-form-submit" :loading="loading" @click="submit">登录</el-button>
        <el-text class="tip" tag="div" size="small" type="info">连续输错 5 次后将要求输入验证码</el-text>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Shop } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { login, getCaptcha, captchaRequired } from '../../api/auth'
import { setToken, setUser } from '../../utils/auth'

const route = useRoute()
const router = useRouter()

const formRef = ref()
const loading = ref(false)
const errorText = ref('')
const needCaptcha = ref(false)
const captchaImage = ref('')

const form = reactive({ username: '', password: '', captchaId: '', captchaCode: '' })

const rules = computed(() => {
  const base = {
    username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
    password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
  }
  if (needCaptcha.value) {
    base.captchaCode = [{ required: true, message: '请输入验证码', trigger: 'blur' }]
  }
  return base
})

const loadCaptcha = async () => {
  try {
    const data = await getCaptcha()
    form.captchaId = data.captchaId
    captchaImage.value = data.image
    form.captchaCode = ''
  } catch (e) {
    ElMessage.error('获取验证码失败：' + e.message)
  }
}

/** 输入用户名后询问是否需要验证码 */
const checkCaptcha = async () => {
  if (!form.username) return
  try {
    needCaptcha.value = await captchaRequired(form.username)
    if (needCaptcha.value) await loadCaptcha()
  } catch {
    needCaptcha.value = false
  }
}

const submit = async () => {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  errorText.value = ''
  loading.value = true
  try {
    const data = await login({
      username: form.username,
      password: form.password,
      captchaId: needCaptcha.value ? form.captchaId : undefined,
      captchaCode: needCaptcha.value ? form.captchaCode : undefined
    })
    setToken(data.accessToken)
    setUser({ username: data.username, nickname: data.nickname })
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    router.replace(redirect)
  } catch (e) {
    errorText.value = e.message
    // 失败后刷新验证码状态与图片
    await checkCaptcha()
    if (needCaptcha.value) await loadCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  if (form.username) await checkCaptcha()
})
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #2c3e50 100%);
}
.login-card { width: 400px; padding: 8px 12px 4px; }
/* 18px = --el-font-size-large，墨色走 --qiqi-color-ink；字重 600 el-text 无 API，保留 */
.brand {
  display: flex; align-items: center; gap: 8px;
  font-weight: 600; justify-content: center; margin-bottom: 20px;
  --el-text-font-size: var(--el-font-size-large);
  --el-text-color: var(--qiqi-color-ink);
}
.captcha-row { display: flex; gap: 10px; width: 100%; }
/* 边框/圆角：Image 组件的 Attributes 全表没有 border/radius，也没有 --el-image-* 变量
   （官方 Image Attributes: src/fit/loading/lazy/scroll-container/alt/referrerpolicy/crossorigin/
    preview-src-list/z-index/initial-index/close-on-press-escape/preview-teleported/infinite/
    zoom-rate/scale/min-scale/max-scale/show-progress），故保留这两行。 */
.captcha-img { height: 40px; border-radius: 4px; cursor: pointer; border: 1px solid #dcdfe6; }
.submit { width: 100%; margin-top: 6px; }
.error { margin-bottom: 12px; }
.tip { margin-top: 12px; text-align: center; }
</style>
