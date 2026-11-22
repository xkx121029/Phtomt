package com.phoneagent.device.vision

/**
 * 端侧 3B 的按需预热闸门。
 *
 * 背景：任务启动时无条件预加载 3B，等于**每个任务**都付一次「GGUF 权重读进常驻内存」；
 * 而混合路由（`smartVisionRoute`）下有些任务全程都是元素树可读的页面外的另一类场景——
 * 只说没人用到端侧视觉的任务（例如纯对话/短任务）也会照样加载一遍。
 *
 * 现在的口径：
 * 1. 只有「本步确实要走端侧 3B」时才开始预热，因此用不到 3B 的任务一次都不加载；
 * 2. 同一任务内只启动一次，避免每一步都起一个加载协程（`NativeVisionEngine.init` 本身幂等，
 *    但没有必要反复尝试）；
 * 3. [reset] 在新任务开始时调用，让下一个任务重新获得一次预热机会；
 * 4. 预热起点仍是本任务第一步，与「任务启动时预热」的重叠程度基本一致，不牺牲首步延迟。
 */
internal class VisionWarmup {
    private var started = false

    /**
     * @param need3b 本步视觉路由是否会用端侧 3B（见 `ObservationPolicy.needExternal3b`）
     * @param alreadyLoaded 模型是否已经在内存里
     * @return true 表示调用方现在应该启动预热
     */
    fun shouldStart(need3b: Boolean, alreadyLoaded: Boolean): Boolean {
        if (!need3b || alreadyLoaded || started) return false
        started = true
        return true
    }

    /** 新任务开始时调用 */
    fun reset() {
        started = false
    }
}
