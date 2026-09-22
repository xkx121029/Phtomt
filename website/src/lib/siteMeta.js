import { reactive } from 'vue'

/**
 * 展示用映射表（发布通道显示名、更新日志分类配色）。
 *
 * 这两张表原本写死在 format.js 里，加一个通道名就得改代码、重新构建镜像。
 * 现在以站点元信息 `site.channelLabels` / `site.sectionTones` 为准，走
 * `PATCH /api/admin/site` 即可增改，前端只是拿它渲染。
 *
 * 下面两份 DEFAULT 仅在「后端不可用 / 字段还没填」时兜底——静态导出（GitHub Pages）
 * 与接口挂掉两条路径都靠它，保证页面不会出现空白标签。
 */

const DEFAULT_CHANNEL_LABELS = {
  stable: '稳定版',
  beta: '测试版',
  nightly: '每日构建',
  dev: '开发版'
}

const DEFAULT_SECTION_TONES = {
  新增: 'ok',
  优化: 'brand',
  变更: 'brand',
  修复: 'amber',
  性能: 'brand',
  测试: 'mist',
  文档: 'mist',
  移除: 'danger',
  安全: 'danger'
}

/** 通道 id → 显示名（如 nightly → 每日构建） */
export const channelLabels = reactive({ ...DEFAULT_CHANNEL_LABELS })

/** 日志分类 → 配色 token（ok / brand / amber / mist / danger） */
export const sectionTones = reactive({ ...DEFAULT_SECTION_TONES })

/**
 * 并入站点元信息里的两张表。
 *
 * 传进来的表是**全量**语义：数据里删掉的键要跟着删，否则改了名字旧键会一直残留；
 * 字段缺失（后端还没配）时原样保留当前值，不动。
 */
function mergeInto(target, defaults, incoming) {
  if (!incoming || typeof incoming !== 'object' || Array.isArray(incoming)) return
  const next = { ...defaults, ...incoming }
  for (const key of Object.keys(target)) {
    if (!(key in next)) delete target[key]
  }
  Object.assign(target, next)
}

export function applySiteMeta(site) {
  mergeInto(channelLabels, DEFAULT_CHANNEL_LABELS, site?.channelLabels)
  mergeInto(sectionTones, DEFAULT_SECTION_TONES, site?.sectionTones)
}
