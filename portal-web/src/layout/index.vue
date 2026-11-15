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
            <el-link class="nav-item" :type="route.path === '/' ? 'primary' : 'default'" :href="href" underline="never" @click="navigate">首页</el-link>
          </router-link>
          <router-link to="/cart" custom v-slot="{ href, navigate }">
            <el-link class="nav-item" :type="route.path === '/cart' ? 'primary' : 'default'" :href="href" underline="never" @click="navigate">
              购物车<el-tag v-if="cartCount" class="badge" size="small" type="danger" effect="dark" round>{{ cartCount }}</el-tag>
            </el-link>
          </router-link>
          <router-link to="/orders" custom v-slot="{ href, navigate }">
            <el-link class="nav-item" :type="route.path === '/orders' ? 'primary' : 'default'" :href="href" underline="never" @click="navigate">我的订单</el-link>
          </router-link>
          <router-link to="/addresses" custom v-slot="{ href, navigate }">
            <el-link class="nav-item" :type="route.path === '/addresses' ? 'primary' : 'default'" :href="href" underline="never" @click="navigate">收货地址</el-link>
          </router-link>
        </nav>

        <div class="right">
          <el-text v-if="logged" class="hello" size="small" data-testid="header-nickname">你好，{{ displayName }}</el-text>
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
/* 品牌与导航的 el-link 不再覆盖任何一个 --el-link-* 变量：全站 el-link 一个样子（14px / 500 / regular，
   悬停变主色）。「当前在哪一页」不再靠钉 color+weight，而是 el-link 的 type prop（当前页 = type="primary"）。
   原来这里钉了 4+4+3 条变量，好处只是"和改前逐像素一样"。 */
.logo {
  display: flex; align-items: center; gap: 6px;
}
.nav { display: flex; gap: 20px; flex: 1; }
.nav-item { padding: 4px 0; }
/* 购物车角标 = el-tag（danger dark round）：红底白字就是 el-tag 的语义色，不再自绘胶囊 */
.badge { margin-left: 3px; }
.right { display: flex; align-items: center; gap: 10px; }
/* 12px 走 size="small"；字色用 el-text 默认的 regular */
.content { max-width: 1180px; margin: 0 auto; padding: 20px 16px 40px; }
</style>
