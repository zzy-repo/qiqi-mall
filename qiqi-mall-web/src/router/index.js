import { createRouter, createWebHistory } from 'vue-router'
import Layout from '../layout/index.vue'

const routes = [
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
        path: 'product/list',
        name: 'ProductList',
        component: () => import('../views/product/list.vue'),
        meta: { title: '商品管理', parent: '商品管理' }
      },
      {
        path: 'product/category',
        name: 'ProductCategory',
        component: () => import('../views/product/category.vue'),
        meta: { title: '商品分类' }
      },
      {
        path: 'order/list',
        name: 'OrderList',
        component: () => import('../views/order/list.vue'),
        meta: { title: '订单列表' }
      },
      {
        path: 'user/list',
        name: 'UserList',
        component: () => import('../views/user/list.vue'),
        meta: { title: '用户列表' }
      },
      {
        path: 'cart',
        name: 'Cart',
        component: () => import('../views/cart/index.vue'),
        meta: { title: '购物车' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} - 七七商城` : '七七商城'
})

export default router
