package com.phoneagent.engine.perception

import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.rules.effectiveLabel
import java.security.MessageDigest

/**
 * 页面指纹：基于可交互控件的 id + type + label + 控件状态 + 边界生成哈希。
 * 页面不变 → 指纹不变；页面变化 → 指纹必变。
 * 用于执行前后验证（确认动作是否真的改变了页面）。
 * 对应文档“第 3 层 页面指纹”。
 *
 * **口径约定（改这里前先读）**：[compute] 是页面级止损（[com.phoneagent.engine.runtime.LoopGuard.onPageOutcome]）
 * 的输入，[computeEffect] 与 `controlStateOf` 是"动作是否生效"的输入。止损的判据必须
 * **不弱于**成功判据：凡成功判据看得到的变化，指纹也必须看得到。否则会出现"每一步都被判成 ✅，
 * 却因为指纹一直没变被判成路径已死"——真机上表现为连点 12 个开关后任务以 STATUS_FAILED 收尾。
 * 反过来更常见也更隐蔽：止损看见了变化、成功判据没看见，于是 AI 被叫去"换入口"——
 * 对开关这种控件，"换入口"就是把它点回去。因此控件状态（`isSelected` / `currentValue`）必须进
 * [compute] **和** [computeEffect]，两者只允许在坐标精度上有差别。
 */
object PageFingerprint {

    /**
     * 同一步内多处以同一份快照求指纹时的复用槽。
     *
     * 一次观察后，观察段、视觉缓存、异常经验、页面记忆、执行段、页面记忆采集会各算一次
     * （每算一次都要全量排序 + SHA256）。它们在同一个快照实例上问的其实是同一个问题。
     *
     * 用「快照实例 + 结果」的单槽原子引用：`===` 判定命中才复用，未命中照常重算，
     * 因此**不存在把旧快照的指纹当成新快照返回**的可能；两个线程同时换页最多多算一次。
     */
    @Volatile
    private var memoMeaningful: Pair<ScreenSnapshot, String>? = null

    @Volatile
    private var memoStrict: Pair<ScreenSnapshot, String>? = null

    @Volatile
    private var memoEffect: Pair<ScreenSnapshot, String>? = null

    /** 计算页面指纹（轻量版，只取关键控件特征）；同一快照实例只算一次 */
    fun compute(snapshot: ScreenSnapshot): String {
        memoStrict?.let { if (it.first === snapshot) return it.second }
        return computeStrict(snapshot).also { memoStrict = snapshot to it }
    }

    /** 计算有意义的指纹；同一快照实例只算一次（口径见 [computeMeaningful]） */
    fun computeMeaningful(snapshot: ScreenSnapshot): String {
        memoMeaningful?.let { if (it.first === snapshot) return it.second }
        return computeMeaningfulUncached(snapshot).also { memoMeaningful = snapshot to it }
    }

    /**
     * 计算「动作是否生效」的指纹；同一快照实例只算一次。
     *
     * 与 [computeMeaningful] 的区别：包含控件状态（[UiElement.isSelected] / [UiElement.currentValue]）。
     * 「点的是行容器、状态变化在内部的 Switch 上」这种页面里，文字与坐标一模一样，只有控件状态变了；
     * 用 [computeMeaningful] 判成功会得出「页面与控件状态均无变化」，而同一个动作在页面级止损那边
     * 却因为 [compute] 看得见状态而算作有推进——AI 于是被叫去"换入口"，对开关就是把它点回去。
     *
     * 与 [compute] 的关系：字段是同源的（类型 + 标签 + 控件状态 + 位置），只有坐标精度不同
     * （这里按 /10 归一以忽略动画抖动）。不变式：**凡 [computeEffect] 看得到的变化，[compute] 必能看到**。
     */
    fun computeEffect(snapshot: ScreenSnapshot): String {
        memoEffect?.let { if (it.first === snapshot) return it.second }
        return computeEffectUncached(snapshot).also { memoEffect = snapshot to it }
    }

    private fun computeStrict(snapshot: ScreenSnapshot): String {
        val sb = StringBuilder()
        sb.append(snapshot.packageName ?: "").append('|')
        // 按坐标排序，保证稳定
        val sorted = snapshot.elements.sortedWith(compareBy<com.phoneagent.domain.model.UiElement> { it.left }.thenBy { it.top })
        for (e in sorted) {
            sb.append(e.viewId ?: "").append(':')
            sb.append(e.type).append(':')
            sb.append(e.effectiveLabel() ?: "").append(':')
            // 控件状态必须进指纹：开关/勾选/滑块/输入值的变化不会改动任何文字或坐标，
            // 却恰恰是 success 判据（controlStateOf）判定"生效"的唯一依据。
            // 少了这两项，连开 12 个开关会算出同一个指纹，被止损判成"同一页面连续无推进"。
            sb.append(e.isSelected ?: "").append(':')
            sb.append(e.currentValue ?: "").append(':')
            sb.append(e.left).append(',').append(e.top).append(',').append(e.right).append(',').append(e.bottom)
            sb.append(';')
        }
        return sha256(sb.toString()).take(16)
    }

    /**
     * 计算有意义的指纹：忽略状态栏时间等动态变化（仅标签与坐标，不含 viewId 中可能变化的序号）。
     *
     * 这里**不**并控件状态：它同时是页面记忆与"动作前页面"的代表，口径一动会波及记忆图谱的行边判定——
     * 开关状态一变就被记成"换了一页"，页面记忆会碎成一堆只差一个开关的节点。
     * 判定"动作是否生效"用 [computeEffect]，页面级止损用 [compute]。
     */
    private fun computeMeaningfulUncached(snapshot: ScreenSnapshot): String {
        val sb = StringBuilder()
        val sorted = snapshot.elements.sortedWith(compareBy<com.phoneagent.domain.model.UiElement> { it.left }.thenBy { it.top })
        for (e in sorted) {
            sb.append(e.type).append(':')
            sb.append(e.effectiveLabel() ?: "").append(':')
            sb.append(e.centerX / 10).append(',').append(e.centerY / 10)
            sb.append(';')
        }
        return sha256(sb.toString()).take(16)
    }

    /**
     * 「动作是否生效」的指纹（口径见 [computeEffect]）：含控件状态，坐标按 /10 归一。
     * 与页面级止损的 [compute] 同源，只差坐标精度。
     */
    private fun computeEffectUncached(snapshot: ScreenSnapshot): String {
        val sb = StringBuilder()
        sb.append(snapshot.packageName ?: "").append('|')
        val sorted = snapshot.elements.sortedWith(compareBy<com.phoneagent.domain.model.UiElement> { it.left }.thenBy { it.top })
        for (e in sorted) {
            sb.append(e.type).append(':')
            sb.append(e.effectiveLabel() ?: "").append(':')
            // 控件状态是"生效"的主要证据：开关/勾选/滑块的变化不改文字也不改坐标
            sb.append(e.isSelected ?: "").append(':')
            sb.append(e.currentValue ?: "").append(':')
            sb.append(e.centerX / 10).append(',').append(e.centerY / 10)
            sb.append(';')
        }
        return sha256(sb.toString()).take(16)
    }

    /** 十六进制字符表：替代 `"%02x".format(it)`（每次指纹计算 32 次 String.format 代价过高） */
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /** 一个字节拆成两个十六进制字符：高 4 位右移、低 4 位掩码 */
    private const val HEX_SHIFT = 4
    private const val HEX_MASK = 0x0F

    /**
     * MessageDigest 非线程安全，按线程缓存复用实例（引擎步进与页面记忆采集在不同线程）。
     * 每次使用前 reset，避免上一次未取尽的摘要状态串扰。
     */
    private val DIGEST = object : ThreadLocal<MessageDigest>() {
        override fun initialValue(): MessageDigest = MessageDigest.getInstance("SHA-256")
    }

    private fun sha256(input: String): String {
        val md = DIGEST.get()!!
        md.reset()
        val bytes = md.digest(input.toByteArray())
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr HEX_SHIFT]).append(HEX_CHARS[v and HEX_MASK])
        }
        return sb.toString()
    }
}