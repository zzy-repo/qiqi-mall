<template>
  <el-container class="layout">
    <!-- 左侧导航栏 -->
    <el-aside :width="isCollapse ? '64px' : '220px'" class="layout-aside">
      <div class="logo">
        <el-icon :size="24" color="#fff"><Shop /></el-icon>
        <span v-show="!isCollapse" class="logo-text">七七商城</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="isCollapse"
        :collapse-transition="false"
        router
        background-color="#1f2d3d"
        text-color="#bfcbd9"
        active-text-color="#ffd04b"
        class="layout-menu"
      >
        <el-sub-menu index="home">
          <template #title>
            <el-icon><HomeFilled /></el-icon>
            <span>首页</span>
          </template>
          <el-menu-item index="/dashboard" data-testid="nav-dashboard">数据概览</el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="member">
          <template #title>
            <el-icon><User /></el-icon>
            <span>用户</span>
          </template>
          <el-menu-item index="/member" data-testid="nav-member">会员列表</el-menu-item>
          <el-menu-item index="/admins" data-testid="nav-admin">管理员</el-menu-item>
          <el-menu-item index="/api-keys" data-testid="nav-api-keys">令牌管理</el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="order">
          <template #title>
            <el-icon><List /></el-icon>
            <span>订单</span>
          </template>
          <el-menu-item index="/order" data-testid="nav-order">订单列表</el-menu-item>
          <el-menu-item index="/return-apply" data-testid="nav-return-apply">退货申请</el-menu-item>
          <el-menu-item index="/return-reason" data-testid="nav-return-reason">退货原因</el-menu-item>
          <el-menu-item index="/order-setting" data-testid="nav-order-setting">订单设置</el-menu-item>
          <el-menu-item index="/warehouse" data-testid="nav-warehouse">仓库管理</el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="product">
          <template #title>
            <el-icon><Goods /></el-icon>
            <span>商品</span>
          </template>
          <el-menu-item index="/product" data-testid="nav-product">商品列表</el-menu-item>
          <el-menu-item index="/category" data-testid="nav-category">商品分类</el-menu-item>
          <el-menu-item index="/product-type" data-testid="nav-product-type">商品类型</el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="audit">
          <template #title>
            <el-icon><Checked /></el-icon>
            <span>订单审核</span>
          </template>
          <el-menu-item index="/order-audit" data-testid="nav-order-audit">审核队列</el-menu-item>
          <el-menu-item index="/audit-policy" data-testid="nav-audit-policy">审核策略</el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="ledger">
          <template #title>
            <el-icon><Tickets /></el-icon>
            <span>台账与对账</span>
          </template>
          <el-menu-item index="/ledger" data-testid="nav-ledger">台账查询</el-menu-item>
          <el-menu-item index="/inventory-reconcile" data-testid="nav-inventory-reconcile">库存对账</el-menu-item>
          <el-menu-item index="/channel" data-testid="nav-channel">多平台接入</el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>

    <el-container class="layout-body">
      <!-- 顶部：折叠按钮 + 面包屑 + 用户 -->
      <el-header class="layout-header">
        <div class="header-left">
          <el-icon class="collapse-btn" :size="20" @click="isCollapse = !isCollapse">
            <Fold v-if="!isCollapse" />
            <Expand v-else />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item
              v-for="item in breadcrumbs"
              :key="item.path"
              :data-testid="item === breadcrumbs[breadcrumbs.length - 1] ? 'breadcrumb-current' : undefined"
            >
              {{ item.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-tag :type="env === 'test' ? 'danger' : 'success'" size="small" effect="dark">
            {{ env === 'test' ? '测试环境' : '开发环境' }}
          </el-tag>
          <el-dropdown>
            <span class="user-info">
              <el-avatar :size="28">{{ avatarText }}</el-avatar>
              <span class="user-name">{{ displayName }}</span>
              <el-icon><CaretBottom /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- 主内容区域 -->
      <el-main class="layout-main">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>

    <!-- 高危写操作确认弹窗：全站只挂这一份（页面调 useDangerConfirm().run 唤起） -->
    <DangerConfirmDialog />
    <!-- 保存冲突弹窗（409 被别人改过 / 400 漏传版本号）：全站只挂这一份（handleSaveError 唤起） -->
    <ConflictDialog />
  </el-container>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
// 菜单图标：main.js 只全局注册了 8 个图标（Goods/Shop/User/List/...），
// 后加的「台账与对账」用 Tickets、「订单审核」用 Checked，按各 view 的惯例在这里局部 import，
// 不改 main.js 的全局注册表（那是别的页面共享的）。
import { Tickets, Checked } from '@element-plus/icons-vue'
import { getUser, clearAuth } from '../utils/auth'
import DangerConfirmDialog from '../components/DangerConfirmDialog.vue'
import ConflictDialog from '../components/ConflictDialog.vue'

const route = useRoute()
const router = useRouter()
const isCollapse = ref(false)
const env = import.meta.env.VITE_APP_ENV

const user = getUser()
const displayName = computed(() => user.nickname || user.username || '管理员')
const avatarText = computed(() => (displayName.value || 'A').slice(0, 1).toUpperCase())

const logout = () => {
  clearAuth()
  ElMessage.success('已退出登录')
  router.replace('/login')
}

const activeMenu = computed(() => route.path)
const breadcrumbs = computed(() => {
  const items = []
  route.matched
    .filter((r) => r.meta?.title)
    .forEach((r) => {
      if (r.meta.parent) items.push({ path: '', title: r.meta.parent })
      items.push({ path: r.path, title: r.meta.title })
    })
  return items
})
</script>

<style scoped>
.layout { height: 100%; }
.layout-aside {
  background: #1f2d3d;
  transition: width 0.28s;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.logo {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  background: #16222e;
  color: #fff;
  white-space: nowrap;
}
.logo-text { font-size: 17px; font-weight: 600; letter-spacing: 1px; }
.layout-menu { border-right: none; flex: 1; }
.layout-body { flex-direction: column; overflow: hidden; }
.layout-header {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #e6e8eb;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}
.header-left { display: flex; align-items: center; gap: 16px; }
.collapse-btn { cursor: pointer; color: #606266; }
.collapse-btn:hover { color: #409eff; }
.header-right { display: flex; align-items: center; gap: 18px; }
.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}
.user-name { font-size: 14px; color: #303133; }
.layout-main {
  padding: 20px;
  background: #f5f7fa;
  overflow-y: auto;
}
.fade-enter-active, .fade-leave-active { transition: opacity 0.18s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
