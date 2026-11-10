import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    // 开发：C 端接口 /portal 代理到后端 dev 8081
    proxy: {
      '/portal': {
        target: mode === 'production' ? 'http://127.0.0.1:8082' : 'http://127.0.0.1:8081',
        changeOrigin: true
      }
    }
  }
}))
