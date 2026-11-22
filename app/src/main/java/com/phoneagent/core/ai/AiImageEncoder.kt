// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.core.ai

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 截图 → `data:image/jpeg;base64,...` 编码，带「最近一张」单槽缓存。
 *
 * 同一步里同一张图会被用到多次（自动描述、hint 定位、发主模型），
 * 而 scale + JPEG + Base64 是纯 CPU 开销；不做缓存就是同一张图编码三遍。
 */
internal class AiImageEncoder {

    /**
     * 同一张截图的 base64 单槽缓存。
     *
     * 同一步里同一张图会被用到多次（每步自动描述、hint 定位、发主模型），而 scale+JPEG+Base64
     * 是纯 CPU 开销：不做缓存就是同一张图编码三遍。这里只留"最近一张"，因为决策链路天然是
     * 一张图走完（用完即换下一个截图对象），多槽反而要处理淘汰。
     */
    private var cachedImageSrc: Bitmap? = null
    private var cachedImageB64: String? = null

    /** 每步开头清一次：截图对象用完会被回收，避免跨步持有一份指向已回收位图的缓存 */
    fun clearImageCache() {
        cachedImageSrc = null
        cachedImageB64 = null
    }

    internal fun base64Image(bitmap: Bitmap): String {
        // 引用相等而非 equals：只认"就是这张对象"，避免同尺寸不同内容的两张图互相串；
        // isRecycled 守卫：位图被回收后必须重新编码，不能把旧字符串再交出去
        if (cachedImageSrc === bitmap && !bitmap.isRecycled) {
            cachedImageB64?.let { return it }
        }
        val scaled = scaleBitmap(bitmap, 1024)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        val bytes = stream.toByteArray()
        val result = "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        // 若产生了缩放副本，用完即回收，避免原生内存泄漏
        if (scaled !== bitmap) scaled.recycle()
        cachedImageSrc = bitmap
        cachedImageB64 = result
        return result
    }

    /** 等比缩放截图，避免超出模型限制 */
    private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val max = maxOf(w, h)
        if (max <= maxDim) return src
        val scale = maxDim.toFloat() / max
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }
}
