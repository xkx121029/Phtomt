import { createRouter, createWebHistory, createWebHashHistory } from 'vue-router'

/**
 * 路由表。
 *
 * 静态导出（GitHub Pages / Gitee Pages）没有服务端重写规则，深链接会 404，
 * 因此那种构建改用 hash 路由；有 Express 托管时用 history 路由，地址更干净。
 */
const history = __STATIC_EXPORT__ ? createWebHashHistory() : createWebHistory()

export const routes = [
  {
    path: '/',
    name: 'home',
    component: () => import('../views/HomeView.vue'),
    meta: { title: '首页', group: '主要' }
  },
  {
    path: '/features',
    name: 'features',
    component: () => import('../views/FeaturesView.vue'),
    meta: { title: '功能特性', group: '主要' }
  },
  {
    path: '/how-it-works',
    name: 'how',
    component: () => import('../views/HowItWorksView.vue'),
    meta: { title: '工作原理', group: '主要' }
  },
  {
    path: '/scenarios',
    name: 'scenarios',
    component: () => import('../views/ScenariosView.vue'),
    meta: { title: '场景示例', group: '主要' }
  },
  {
    path: '/download',
    name: 'download',
    component: () => import('../views/DownloadView.vue'),
    meta: { title: '下载', group: '主要' }
  },
  {
    path: '/changelog',
    name: 'changelog',
    component: () => import('../views/ChangelogView.vue'),
    meta: { title: '更新日志', group: '内容' }
  },
  {
    path: '/changelog/:version',
    name: 'changelog-detail',
    component: () => import('../views/ChangelogDetailView.vue'),
    meta: { title: '版本详情', group: '内容' }
  },
  {
    path: '/docs',
    name: 'docs',
    component: () => import('../views/DocsView.vue'),
    meta: { title: '文档中心', group: '内容' }
  },
  {
    path: '/docs/:slug',
    name: 'docs-detail',
    component: () => import('../views/DocDetailView.vue'),
    meta: { title: '文档', group: '内容' }
  },
  {
    path: '/faq',
    name: 'faq',
    component: () => import('../views/FaqView.vue'),
    meta: { title: '常见问题', group: '内容' }
  },
  {
    path: '/roadmap',
    name: 'roadmap',
    component: () => import('../views/RoadmapView.vue'),
    meta: { title: '路线图', group: '内容' }
  },
  {
    path: '/about',
    name: 'about',
    component: () => import('../views/AboutView.vue'),
    meta: { title: '关于项目', group: '其他' }
  },
  {
    path: '/admin',
    component: () => import('../views/admin/AdminLayout.vue'),
    meta: { title: '管理后台', group: '管理', admin: true },
    children: [
      { path: '', name: 'admin-overview', component: () => import('../views/admin/AdminOverview.vue'), meta: { title: '概览', admin: true } },
      { path: 'releases', name: 'admin-releases', component: () => import('../views/admin/AdminReleases.vue'), meta: { title: '版本与 APK', admin: true } },
      { path: 'site', name: 'admin-site', component: () => import('../views/admin/AdminSite.vue'), meta: { title: '站点信息', admin: true } },
      { path: 'features', name: 'admin-features', component: () => import('../views/admin/AdminFeatures.vue'), meta: { title: '功能特性', admin: true } },
      { path: 'changelog', name: 'admin-changelog', component: () => import('../views/admin/AdminChangelog.vue'), meta: { title: '更新日志', admin: true } },
      { path: 'docs', name: 'admin-docs', component: () => import('../views/admin/AdminDocs.vue'), meta: { title: '文档', admin: true } },
      { path: 'faq', name: 'admin-faq', component: () => import('../views/admin/AdminFaq.vue'), meta: { title: '常见问题', admin: true } },
      { path: 'audit', name: 'admin-audit', component: () => import('../views/admin/AdminAudit.vue'), meta: { title: '操作留痕', admin: true } }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('../views/NotFoundView.vue'),
    meta: { title: '页面不存在' }
  }
]

const router = createRouter({
  history,
  routes,
  scrollBehavior(to, from, saved) {
    if (saved) return saved
    if (to.hash) return { el: to.hash, top: 90, behavior: 'smooth' }
    return { top: 0 }
  }
})

router.afterEach((to) => {
  const title = to.meta?.title
  document.title = title && to.name !== 'home' ? `${title} · Happy Phone Agent` : 'Happy Phone Agent — 让手机自己动手'
})

export default router
