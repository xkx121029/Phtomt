import { ref } from 'vue'
import { ApiError, api, isStaticBuild } from '../../api/client.js'

/**
 * 集合 CRUD 的公共逻辑。
 *
 * features / docs / faq / changelog 四个集合的接口形状完全一致（列表读原始集合，
 * 单条走 POST / PUT :id / DELETE :id），差别只在主键字段和表单字段上。
 * 把读写抽到这里，页面就只剩「字段定义 + 表单」。
 */
export function useCollection(name, idKey) {
  const items = ref([])
  const loading = ref(false)
  const busy = ref('')
  const error = ref('')
  const notice = ref('')

  function clear() {
    error.value = ''
    notice.value = ''
  }

  function report(err, fallback) {
    error.value = err instanceof ApiError ? err.message : fallback
    notice.value = ''
  }

  async function load() {
    if (isStaticBuild) {
      error.value = '当前是静态构建，没有可写的后端。请在部署了 Node 服务的环境下使用后台。'
      return
    }
    loading.value = true
    clear()
    try {
      const data = await api.adminGet(`/api/admin/content/${name}`)
      items.value = Array.isArray(data) ? data : []
    } catch (err) {
      report(err, '读取失败')
    } finally {
      loading.value = false
    }
  }

  async function create(item) {
    busy.value = 'create'
    clear()
    try {
      await api.post(`/api/admin/${name}`, item, true)
      await load()
      notice.value = '已新增'
      return true
    } catch (err) {
      report(err, '新增失败')
      return false
    } finally {
      busy.value = ''
    }
  }

  async function update(id, patch) {
    busy.value = 'update'
    clear()
    try {
      await api.put(`/api/admin/${name}/${encodeURIComponent(id)}`, patch, true)
      await load()
      notice.value = '已保存'
      return true
    } catch (err) {
      report(err, '保存失败')
      return false
    } finally {
      busy.value = ''
    }
  }

  async function remove(id) {
    busy.value = 'remove'
    clear()
    try {
      await api.del(`/api/admin/${name}/${encodeURIComponent(id)}`, true)
      await load()
      notice.value = `已删除 ${id}`
      return true
    } catch (err) {
      report(err, '删除失败')
      return false
    } finally {
      busy.value = ''
    }
  }

  /** 整包替换：脚本化同步或大改时用，比逐条 PUT 少很多往返 */
  async function replaceAll(list) {
    busy.value = 'replace'
    clear()
    try {
      const res = await api.put(`/api/admin/${name}`, list, true)
      await load()
      notice.value = `已整包写入 ${res?.count ?? list.length} 条`
      return true
    } catch (err) {
      report(err, '整包写入失败')
      return false
    } finally {
      busy.value = ''
    }
  }

  return { items, loading, busy, error, notice, load, create, update, remove, replaceAll, clear }
}
