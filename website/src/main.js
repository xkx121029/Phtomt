import { createApp } from 'vue'
import App from './App.vue'
import router from './router/index.js'
import { vReveal } from './composables/useReveal.js'

import './styles/tokens.css'
import './styles/base.css'
import './styles/prose.css'

const app = createApp(App).use(router).directive('reveal', vReveal)

// 等目标路由解析完再挂载。
// 曾在这里直接 mount('#app')：首帧只画出常驻的页头页脚（正文分块还没到，.main 是空的，
// 页脚就贴在顶栏下方、落在视口里）；等 HomeView 分块回来，几千像素灌进 .main 把页脚
// 从 y≈66 顶到 y≈7000，Lighthouse 的 CLS 因此高达 0.87。先 isReady 再挂载，
// 页头/页脚/正文在同一帧落地，页脚从一开始就在折叠线以下。
router.isReady().then(() => app.mount('#app'))
