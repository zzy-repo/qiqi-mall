import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { Goods, Shop } from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import './assets/styles/main.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
// 只注册 portal 模板真正用到的全局图标（各 view 内部用到的图标在各自组件里局部 import）
app.component('Goods', Goods)
app.component('Shop', Shop)
app.use(createPinia()).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
console.log('qiqi-portal env:', import.meta.env.VITE_APP_ENV)
