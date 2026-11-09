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
        path: 'attribute',
        name: 'Attribute',
        component: () => import('../views/attribute/index.vue'),
        meta: { title: '属性' }
      },
      {
        path: 'product',
        name: 'Product',
        component: () => import('../views/product/index.vue'),
        meta: { title: '商品' }
      },
      {
        path: 'sku',
        name: 'Sku',
        component: () => import('../views/sku/index.vue'),
        meta: { title: 'SKU' }
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
