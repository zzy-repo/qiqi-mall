<template>
  <div class="portal">
    <header class="topbar">
      <div class="inner">
        <router-link to="/" custom v-slot="{ href, navigate }">
          <el-link class="logo" :href="href" underline="never" @click="navigate">
            <el-icon :size="22"><Shop /></el-icon>
            <span>七七商城</span>
          </el-link>
        </router-link>

        <nav class="nav">
          <router-link to="/" custom v-slot="{ href, navigate }">
            <el-link class="nav-item" :class="{ active: route.path === '/' }" :href="href" underline="never" @click="navigate">首页</el-link>
          </router-link>
          <router-link to="/cart" custom v-slot="{ href, navigate }">
            <el-link class="nav-item" :class="{ active: route.path === '/cart' }" :href="href" underline="never" @click="navigate">
              购物车<span v-if="cartCount" class="badge">{{ cartCount }}</span>
            </el-link>
          </router-link>
          <router-link to="/orders" custom v-slot="{ href, navigate }">
            <el-link class="nav-item" :class="{ active: route.path === '/orders' }" :href="href" underline="never" @click="navigate">我的订单</el-link>
          </router-link>
          <router-link to="/addresses" custom v-slot="{ href, navigate }">
            <el-link class="nav-item" :class="{ active: route.path === '/addresses' }" :href="href" underline="never" @click="navigate">收货地址</el-link>
          </router-link>
        </nav>

        <div class="right">
          <el-text v-if="logged" class="hello" data-testid="header-nickname">你好，{{ displayName }}</el-text>
          <template v-if="logged">
            <el-button link type="primary" data-testid="header-logout" @click="logout">退出</el-button>
          </template>
          <el-button v-else type="primary" size="small" @click="goLogin">登录 / 注册</el-button>
        </div>
      </div>
    </header>

    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getToken, getUser, clearAuth } from '../utils/auth'
import { listCart } from '../api/cart'
import { listLocalCart } from '../utils/localCart'

const route = useRoute()
const router = useRouter()

const logged = computed(() => !!getToken())
const displayName = computed(() => {
  const user = getUser()
  return user.nickname || user.username || '买家'
})

const cartCount = ref(0)

const refreshCartCount = async () => {
  if (logged.value) {
    try {
      const rows = await listCart()
      cartCount.value = rows.reduce((sum, i) => sum + i.quantity, 0)
    } catch {
      cartCount.value = 0
    }
  } else {
    cartCount.value = listLocalCart().reduce((sum, i) => sum + i.quantity, 0)
  }
}

const goLogin = () => router.push({ path: '/login', query: { redirect: route.fullPath } })
const logout = () => {
  clearAuth()
  ElMessage.success('已退出登录')
  router.push('/')
  refreshCartCount()
}

watch(() => route.fullPath, () => refreshCartCount())
onMounted(refreshCartCount)
</script>

<style scoped>
.portal { min-height: 100vh; background: #f5f7fa; }
.topbar { background: #fff; border-bottom: 1px solid #e6e8eb; }
.inner {
  max-width: 1180px; margin: 0 auto; height: 60px;
  display: flex; align-items: center; gap: 32px; padding: 0 16px;
}
/* 品牌与导航的字号/字重/字色，全部由 el-link 的公开变量表达（link.scss:22-24 消费
   --el-link-font-size/-font-weight/-text-color；定义见 common/var.scss:1480-1490）。
   --el-link-hover-text-color 也钉回原色：改前悬停不变色，不钉就会变成 el-link 默认的主色。 */
.logo {
  display: flex; align-items: center; gap: 6px;
  --el-link-font-size: var(--el-font-size-large);
  --el-link-font-weight: 600;
  --el-link-text-color: var(--qiqi-color-ink);
  --el-link-hover-text-color: var(--qiqi-color-ink);
}
.nav { display: flex; gap: 20px; flex: 1; }
.nav-item {
  padding: 4px 0;
  --el-link-font-size: var(--el-font-size-base);
  --el-link-font-weight: 400;
  --el-link-text-color: var(--el-text-color-regular);
  --el-link-hover-text-color: var(--el-text-color-regular);
}
.nav-item.active {
  --el-link-text-color: var(--el-color-primary);
  --el-link-font-weight: 600;
  --el-link-hover-text-color: var(--el-color-primary);
}
.badge {
  background: #f56c6c; color: #fff; font-size: 11px; border-radius: 8px;
  padding: 0 5px; margin-left: 3px;
}
.right { display: flex; align-items: center; gap: 10px; }
/* 13px 走 el-text 的官方变量（--el-font-size-small 就是 13px）；字色用 el-text 默认的 regular。 */
.hello { --el-text-font-size: var(--el-font-size-small); }
.content { max-width: 1180px; margin: 0 auto; padding: 20px 16px 40px; }
</style>
