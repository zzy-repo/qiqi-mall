import { createRouter, createWebHistory } from 'vue-router'
import Layout from '../layout/index.vue'
import { getToken } from '../utils/auth'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/login/index.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/dashboard/index.vue'),
        meta: { title: '数据概览' }
      },
      {
        path: 'category',
        name: 'Category',
        component: () => import('../views/category/index.vue'),
        meta: { title: '商品分类' }
      },
      {
        path: 'product-type',
        name: 'ProductType',
        component: () => import('../views/product-type/index.vue'),
        meta: { title: '商品类型' }
      },
      {
        path: 'product',
        name: 'Product',
        component: () => import('../views/product/index.vue'),
        meta: { title: '商品列表' }
      },
      {
        path: 'member',
        name: 'Member',
        component: () => import('../views/member/index.vue'),
        meta: { title: '会员列表' }
      },
      {
        path: 'member/:memberId/addresses',
        name: 'MemberAddresses',
        component: () => import('../views/member/addresses.vue'),
        meta: { title: '收货地址', parent: '会员列表' }
      },
      {
        path: 'member/:memberId/carts',
        name: 'MemberCarts',
        component: () => import('../views/member/carts.vue'),
        meta: { title: '购物车', parent: '会员列表' }
      },
      {
        // 注意：不能叫 'admin'。nginx 的 `location /admin/` 把 /admin/** 全部代理给后端 API，
        // SPA 路由占用 'admin' 会导致硬刷新（F5）直接吃到 401 白屏。
        // 前端路由用 'admins'，后端接口前缀仍是 /admin。
        path: 'admins',
        name: 'Admin',
        component: () => import('../views/admin/index.vue'),
        meta: { title: '管理员' }
      },
      {
        path: 'api-keys',
        name: 'ApiKeys',
        component: () => import('../views/api-key/index.vue'),
        meta: { title: '令牌管理' }
      },
      {
        path: 'order',
        name: 'Order',
        component: () => import('../views/order/index.vue'),
        meta: { title: '订单列表' }
      },
      {
        path: 'return-apply',
        name: 'ReturnApply',
        component: () => import('../views/return-apply/index.vue'),
        meta: { title: '退货申请' }
      },
      {
        path: 'return-reason',
        name: 'ReturnReason',
        component: () => import('../views/return-reason/index.vue'),
        meta: { title: '退货原因' }
      },
      {
        path: 'warehouse',
        name: 'Warehouse',
        component: () => import('../views/warehouse/index.vue'),
        meta: { title: '仓库管理' }
      },
      {
        path: 'order-setting',
        name: 'OrderSetting',
        component: () => import('../views/order-setting/index.vue'),
        meta: { title: '订单设置' }
      },
      {
        path: 'product-type/:typeId/attributes',
        name: 'TypeAttributes',
        component: () => import('../views/product-type/attributes.vue'),
        meta: { title: '属性管理', parent: '商品类型' }
      },
      {
        path: 'product/:productId/skus',
        name: 'ProductSkus',
        component: () => import('../views/product/skus.vue'),
        meta: { title: 'SKU 管理', parent: '商品列表' }
      },
      {
        // 台账查询（订单/包裹/售后/审计/WMS/库存 的全链路状态流水，只读）
        path: 'ledger',
        name: 'Ledger',
        component: () => import('../views/ledger/index.vue'),
        meta: { title: '台账查询' }
      },
      {
        // 销售单自动审核队列（chip 筛选 + 判定徽标 + 「差在哪」抽屉）
        // 路由段**不能**叫 admin：nginx 的 location /admin/ 会把 /admin/** 全代理给后端，
        // 前端路由占用同名段会让 F5 直接吃到 401 白屏（见 /admins 那条注释）。
        path: 'order-audit',
        name: 'OrderAudit',
        component: () => import('../views/order-audit/index.vue'),
        meta: { title: '审核队列' }
      },
      {
        // 自动审核策略配置（AUTO / BLOCK / EXCEPTION 三 tab + 条件编辑器 + 影响预演）
        path: 'audit-policy',
        name: 'AuditPolicy',
        component: () => import('../views/audit-policy/index.vue'),
        meta: { title: '审核策略' }
      },
      {
        // 库存对账（SUM 库存桶 vs sku.stock 缓存，偏差排查）
        // 同样不能以 admin 开头，理由见上面 /admins 那条注释
        path: 'inventory-reconcile',
        name: 'InventoryReconcile',
        component: () => import('../views/inventory-reconcile/index.vue'),
        meta: { title: '库存对账' }
      }
    ]
  },
  // 未知地址一律回后台首页
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局路由守卫：无 token 一律回登录页并记录 redirect
router.beforeEach((to) => {
  const token = getToken()
  if (to.meta?.public) {
    // 已登录访问登录页 → 直接进后台
    if (token && to.path === '/login') return { path: '/dashboard' }
    return true
  }
  if (!token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} - 七七商城` : '七七商城'
})

export default router
