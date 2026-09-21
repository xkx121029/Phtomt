import { ref, watchEffect } from 'vue'

const KEY = 'phtomt-theme'
const stored = (() => {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
})()

const prefersDark =
  typeof matchMedia === 'function' ? matchMedia('(prefers-color-scheme: dark)').matches : false

export const theme = ref(stored || (prefersDark ? 'dark' : 'light'))

watchEffect(() => {
  document.documentElement.dataset.theme = theme.value
  try {
    localStorage.setItem(KEY, theme.value)
  } catch {
    /* 忽略：隐私模式下写不进去也不影响本次会话 */
  }
})

export function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
}

export function useTheme() {
  return { theme, toggleTheme }
}
