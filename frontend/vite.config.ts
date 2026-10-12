
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,

    // 允许 Cloudflare 公网域名访问
    allowedHosts: [
      'wrap-calendars-armstrong-poor.trycloudflare.com'
    ],

    // 保留 Spring Boot 后端接口代理
    proxy: {
      '/api': 'http://localhost:8080'
    }
  }
})
