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
        path: 'admin',
        name: 'Admin',
        component: () => import('../views/admin/index.vue'),
        meta: { title: '管理员' }
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
