import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 两种构建目标：
 *  - 默认（`npm run build`）：产物交给 Express 托管，base 为 `/`，前端走 history 路由。
 *  - 静态导出（`npm run build:static`，供 GitHub Pages / Gitee Pages）：
 *    没有后端，改为 hash 路由 + 相对 base，所有内容从 `src/data/fallback.js` 快照读取。
 */
const staticExport = process.env.STATIC_EXPORT === '1'

export default defineConfig({
  base: staticExport ? './' : '/',
  plugins: [vue()],
  define: {
    __STATIC_EXPORT__: JSON.stringify(staticExport)
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  build: {
    outDir: staticExport ? 'dist-static' : 'dist',
    emptyOutDir: true,
    target: 'es2020',
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['vue', 'vue-router']
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://127.0.0.1:5180', changeOrigin: true },
      '/apk': { target: 'http://127.0.0.1:5180', changeOrigin: true }
    }
  }
})
