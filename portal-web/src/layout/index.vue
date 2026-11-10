<template>
  <div class="portal">
    <header class="topbar">
      <div class="inner">
        <router-link to="/" class="logo">
          <el-icon :size="22"><Shop /></el-icon>
          <span>七七商城</span>
        </router-link>

        <nav class="nav">
          <router-link to="/" class="nav-item" :class="{ active: route.path === '/' }">首页</router-link>
          <router-link to="/cart" class="nav-item" :class="{ active: route.path === '/cart' }">
            购物车<span v-if="cartCount" class="badge">{{ cartCount }}</span>
          </router-link>
          <router-link to="/orders" class="nav-item" :class="{ active: route.path === '/orders' }">我的订单</router-link>
          <router-link to="/addresses" class="nav-item" :class="{ active: route.path === '/addresses' }">收货地址</router-link>
        </nav>

        <div class="right">
          <template v-if="logged">
            <span class="hello">你好，{{ displayName }}</span>
            <el-button link type="primary" @click="logout">退出</el-button>
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
.logo { display: flex; align-items: center; gap: 6px; font-size: 18px; font-weight: 600; color: #1f2d3d; text-decoration: none; }
.nav { display: flex; gap: 20px; flex: 1; }
.nav-item { color: #606266; text-decoration: none; font-size: 14px; padding: 4px 0; }
.nav-item.active { color: #409eff; font-weight: 600; }
.badge {
  background: #f56c6c; color: #fff; font-size: 11px; border-radius: 8px;
  padding: 0 5px; margin-left: 3px;
}
.right { display: flex; align-items: center; gap: 10px; }
.hello { font-size: 13px; color: #606266; }
.content { max-width: 1180px; margin: 0 auto; padding: 20px 16px 40px; }
</style>
