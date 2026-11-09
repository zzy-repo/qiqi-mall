import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    // 开发模式 5173 代理到后端 dev 8081；prod 预览模式代理到 8082
    proxy: {
      '/api': {
        target: mode === 'production' ? 'http://127.0.0.1:8082' : 'http://127.0.0.1:8081',
        changeOrigin: true
      }
    }
  }
}))
