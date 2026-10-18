package com.phoneagent.engine.decision

import com.phoneagent.device.vision.DetectedControl

/**
 * 视觉结果按页面指纹缓存（决策逻辑改进，阶段 B2）。
 *
 * 问题：cloudDecide 每一步都重跑视觉链路（端侧 3B / 云端视觉 / OCR，10-40s + 额度），
 * 但页面未变时（连续两次决策间 AI 只在等待/输入/页面无跳转），视觉产物完全相同——白跑。
 *
 * 方案：以「页面指纹 + 截图尺寸 + 任务 id」为键缓存上一次视觉产物（desc / localRegions / externalUsed）。
 * 命中即整段跳过视觉调用；指纹变化（换页）、截图尺寸变化（旋转/分屏）或任务切换（跨任务不复用）即失效。
 */
internal class VisionResultCache {
    /** 一次视觉链路的产物 */
    internal data class Entry(
        val desc: String?,
        val regions: List<DetectedControl>?,
        val usedExternal: Boolean,
    )

    private data class CacheKey(val fingerprint: String, val width: Int, val height: Int, val taskId: Long)

    private var key: CacheKey? = null
    private var entry: Entry? = null

    /** 命中返回上一次产物；指纹为空（无法判定页面）或未命中返回 null */
    fun get(fingerprint: String, width: Int, height: Int, taskId: Long): Entry? {
        if (fingerprint.isBlank()) return null
        val k = key ?: return null
        if (k.fingerprint != fingerprint || k.width != width || k.height != height || k.taskId != taskId) return null
        return entry
    }

    fun put(fingerprint: String, width: Int, height: Int, taskId: Long, e: Entry) {
        if (fingerprint.isBlank()) return
        key = CacheKey(fingerprint, width, height, taskId)
        entry = e
    }

    /** 任务收尾/显式换页时清空 */
    fun invalidate() {
        key = null
        entry = null
    }
}
