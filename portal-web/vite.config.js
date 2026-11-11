import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: Number(process.env.VITE_PORT || 5173),
    // 开发：C 端接口 /portal 代理到后端 dev 8081
    proxy: {
      '/portal': {
        // 优先级：VITE_PROXY_TARGET（e2e 分片专用后端）> test 模式（容器后端 8082）> dev 后端 8081
        // e2e 并行时每个 shard 起一套 vite，指到自己那套后端（8091/8092），互不干扰
        target: process.env.VITE_PROXY_TARGET || (mode === 'test' ? 'http://127.0.0.1:8082' : 'http://127.0.0.1:8081'),
        changeOrigin: true
      },
      // 商品图片存的是相对路径 /qiqi-mall/**，dev 栈把它反代到本地 MinIO
      '/qiqi-mall': {
        target: 'http://127.0.0.1:9000',
        changeOrigin: true
      }
    }
  }
}))
