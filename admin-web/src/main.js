import { createApp } from 'vue'
import { createPinia } from 'pinia'
// 按需引入（B2）：不再 import ElementPlus 整包、不再 app.use(ElementPlus)、不再引
// element-plus/dist/index.css。模板里的 <el-xxx> 与 v-loading 由 unplugin 解析器自动
// import 并注入各自样式（见 vite.config.js）。
//
// 但仍需要两样东西：
// 1) 中文 locale：组件库默认 en，pagination/message-box 等文案会变英文。全量模式下由
//    app.use(ElementPlus, { locale }) 注入，按需模式下改为直接调官方导出的
//    provideGlobalConfig（installer 内部做的就是这一句）。
// 2) 函数式组件样式：ElMessage / ElMessageBox 是 JS 调用、不出现在模板里，解析器不会
//    给它注入样式，必须显式引（grep 证据：两个项目里只有这两个函数式组件被使用）。
import { provideGlobalConfig } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import { Goods, Shop, User, List, HomeFilled, CaretBottom, Fold, Expand } from '@element-plus/icons-vue'
import './assets/styles/main.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
// 只注册 admin 模板真正用到的全局图标（各 view 内部用到的图标在各自组件里局部 import）
app.component('Goods', Goods)
app.component('Shop', Shop)
app.component('User', User)
app.component('List', List)
app.component('HomeFilled', HomeFilled)
app.component('CaretBottom', CaretBottom)
app.component('Fold', Fold)
app.component('Expand', Expand)
app.use(createPinia()).use(router)
// 等价于原先 app.use(ElementPlus, { locale: zhCn }) 里的 locale 注入（global=true 同 installer）
provideGlobalConfig({ locale: zhCn }, app, true)
app.mount('#app')
console.log('qiqi-mall env:', import.meta.env.VITE_APP_ENV)
