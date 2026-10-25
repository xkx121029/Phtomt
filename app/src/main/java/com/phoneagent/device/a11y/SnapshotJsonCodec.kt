package com.phoneagent.device.a11y

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.UiElement
import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider captureScreen JSON → 主应用领域模型。
 *
 * Provider 侧的序列化字段与 com.phoneagent.domain.model.ScreenSnapshot/UiElement 一一对应
 * （见 ProviderAccessibilityService.elementJson）。可空字段在无值时省键，因此这里一律按缺省处理；
 * semanticId / ratioX / ratioY / priority 由主应用侧标注器补齐，Provider 不产出。
 */
object SnapshotJsonCodec {

    /** 解码结果：快照本体 + Provider 带回的元素树统计 */
    data class Decoded(
        val snapshot: ScreenSnapshot,
        val stats: String,
    )

    /** 解析失败（空串/非法 JSON）返回 null，由调用方降级为「无障碍不可用」 */
    fun decode(raw: String?): Decoded? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            Decoded(
                snapshot = ScreenSnapshot(
                    packageName = obj.stringOrNull("packageName"),
                    missingAccessibility = obj.optBoolean("missingAccessibility", false),
                    screenWidth = obj.optInt("screenWidth", 0),
                    screenHeight = obj.optInt("screenHeight", 0),
                    elements = obj.optJSONArray("elements")?.let { parseElements(it) } ?: emptyList(),
                ),
                stats = obj.stringOrNull("stats") ?: "无统计",
            )
        }.getOrNull()
    }

    /** 解析落点审计 JSON：{label,viewId,className,clickable}；解析失败返回 null */
    fun decodeNodeHit(raw: String?): NodeHit? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(raw)
            NodeHit(
                label = obj.stringOrNull("label"),
                viewId = obj.stringOrNull("viewId"),
                className = obj.stringOrNull("className") ?: "Unknown",
                clickable = obj.optBoolean("clickable", false),
            )
        }.getOrNull()
    }

    private fun parseElements(array: JSONArray): List<UiElement> = buildList {
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { add(parseElement(it)) }
        }
    }

    private fun parseElement(obj: JSONObject): UiElement = UiElement(
        index = obj.optInt("index", 0),
        className = obj.stringOrNull("className") ?: "Unknown",
        type = obj.stringOrNull("type") ?: "View",
        text = obj.stringOrNull("text"),
        contentDescription = obj.stringOrNull("contentDescription"),
        isSelected = if (obj.has("isSelected") && !obj.isNull("isSelected")) obj.optBoolean("isSelected") else null,
        currentValue = obj.stringOrNull("currentValue"),
        x = obj.optInt("x", 0),
        y = obj.optInt("y", 0),
        left = obj.optInt("left", 0),
        top = obj.optInt("top", 0),
        right = obj.optInt("right", 0),
        bottom = obj.optInt("bottom", 0),
        clickable = obj.optBoolean("clickable", false),
        longClickable = obj.optBoolean("longClickable", false),
        scrollable = obj.optBoolean("scrollable", false),
        editable = obj.optBoolean("editable", false),
        viewId = obj.stringOrNull("viewId"),
        semanticId = obj.stringOrNull("semanticId"),
        packageName = obj.stringOrNull("packageName"),
        isEnabled = obj.optBoolean("isEnabled", true),
        isVisibleToUser = obj.optBoolean("isVisibleToUser", true),
        childCount = obj.optInt("childCount", 0),
        priority = obj.stringOrNull("priority") ?: "medium",
        ratioX = if (obj.has("ratioX") && !obj.isNull("ratioX")) obj.optDouble("ratioX").toFloat() else null,
        ratioY = if (obj.has("ratioY") && !obj.isNull("ratioY")) obj.optDouble("ratioY").toFloat() else null,
    )

    /** 缺键或 JSON null 都返回 null；org.json 的 optString 对两者会给出 ""，不能直接使用 */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
}
