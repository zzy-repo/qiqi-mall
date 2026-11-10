import { createRouter, createWebHistory } from 'vue-router'
import Layout from '../layout/index.vue'
import { getToken } from '../utils/auth'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/login/index.vue'),
    meta: { title: '登录 / 注册', public: true }
  },
  {
    path: '/',
    component: Layout,
    children: [
      { path: '', name: 'Home', component: () => import('../views/home/index.vue'), meta: { title: '首页' } },
      { path: 'product/:id', name: 'ProductDetail', component: () => import('../views/product/detail.vue'), meta: { title: '商品详情' } },
      { path: 'cart', name: 'Cart', component: () => import('../views/cart/index.vue'), meta: { title: '购物车' } },
      { path: 'checkout', name: 'Checkout', component: () => import('../views/checkout/index.vue'), meta: { title: '结算', requiresAuth: true } },
      { path: 'orders', name: 'Orders', component: () => import('../views/order/index.vue'), meta: { title: '我的订单', requiresAuth: true } },
      { path: 'addresses', name: 'Addresses', component: () => import('../views/address/index.vue'), meta: { title: '收货地址', requiresAuth: true } }
    ]
  },
  // 未知地址一律回首页
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({ history: createWebHistory(), routes })

// 懒登录拦截：需要登录的页面统一回登录页并记录回跳地址
router.beforeEach((to) => {
  const token = getToken()
  if (to.meta?.public) return true
  if (to.meta?.requiresAuth && !token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} - 七七商城` : '七七商城'
})

export default router
