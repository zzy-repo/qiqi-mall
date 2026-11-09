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
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon>
          <template #title>数据概览</template>
        </el-menu-item>
        <el-menu-item index="/category">
          <el-icon><Folder /></el-icon>
          <template #title>商品分类</template>
        </el-menu-item>
        <el-menu-item index="/product-type">
          <el-icon><Collection /></el-icon>
          <template #title>商品类型</template>
        </el-menu-item>
        <el-menu-item index="/attribute">
          <el-icon><Setting /></el-icon>
          <template #title>属性</template>
        </el-menu-item>
        <el-menu-item index="/product">
          <el-icon><Goods /></el-icon>
          <template #title>商品</template>
        </el-menu-item>
        <el-menu-item index="/sku">
          <el-icon><Tickets /></el-icon>
          <template #title>SKU</template>
        </el-menu-item>
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
            <el-breadcrumb-item v-for="item in breadcrumbs" :key="item.path">
              {{ item.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-tag :type="env === 'prod' ? 'danger' : 'success'" size="small" effect="dark">
            {{ env === 'prod' ? '生产环境' : '开发环境' }}
          </el-tag>
          <el-dropdown>
            <span class="user-info">
              <el-avatar :size="28" src="" >Z</el-avatar>
              <span class="user-name">zzy</span>
              <el-icon><CaretBottom /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item>个人中心</el-dropdown-item>
                <el-dropdown-item divided>退出登录</el-dropdown-item>
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
  </el-container>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const isCollapse = ref(false)
const env = import.meta.env.VITE_APP_ENV

const activeMenu = computed(() => route.path)
const breadcrumbs = computed(() =>
  route.matched
    .filter((r) => r.meta?.title)
    .map((r) => ({ path: r.path, title: r.meta.title }))
)
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
