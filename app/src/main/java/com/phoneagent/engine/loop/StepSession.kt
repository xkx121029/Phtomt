package com.phoneagent.engine.loop

import android.graphics.Bitmap
import com.phoneagent.core.ai.ChatMessageDto
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.domain.model.AgentIntent
import com.phoneagent.domain.model.ScreenSnapshot
import com.phoneagent.domain.model.TaskPlan
import com.phoneagent.engine.PromptLang
import com.phoneagent.engine.perception.AnnotatedPage

/**
 * 一步主循环的共享上下文（阶段 B2）。
 *
 * 观察 → 决策 → 执行三段本来通过循环里的局部变量传递产物（snapshot / annotated / semanticSnapshot /
 * screenshot / pageMemoryHint / intent）。拆成独立阶段后，这些产物必须有个明确的载体，
 * 否则每个阶段都要重复一串「入参 + 出参」，主循环又会变成新的胶水层。
 *
 * 字段按阶段归属：
 * - 构造时确定、整轮不变：任务、计划、步号、设置快照、提示词语言、消息历史
 * - 观察段写入：快照、标注、语义快照、截图、页面记忆提示、无障碍在线状态
 * - 决策段写入：意图、是否来自端侧
 * - 执行段只读上述产物，并负责把这一轮的结果回注 [messages]
 */
internal class StepSession(
    val task: String,
    val plan: TaskPlan?,
    val step: Int,
    val settingsVal: AppSettings.Settings,
    val lang: PromptLang,
    /** 本任务共享的对话历史；执行段会把执行结果追加进去，供下一轮决策 */
    val messages: MutableList<ChatMessageDto>,
) {
    /**
     * 本任务是否曾观测到无障碍在线。
     * 用于识别「任务中途掉线」：服务消失且此前在线过 → 暂停引导用户重新授权，
     * 而不是继续消耗步数盲跑。跨步存活，因此放在会话而不是单步局部变量。
     */
    var a11ySeenAlive: Boolean = false

    /** 本步快照（可能已被广告过滤清洗过） */
    var snapshot: ScreenSnapshot = ScreenSnapshot()

    /** 页面标注结果（页面类型 / 语义 id 表） */
    var annotated: AnnotatedPage? = null

    /** 回填过 semanticId 的快照：端侧决策与转译层的语义定位依赖它 */
    var semanticSnapshot: ScreenSnapshot = snapshot

    /** 本步截图；未开启截图且元素树可读时为 null */
    var screenshot: Bitmap? = null

    /** 页面记忆命中后注入决策提示的素材（未开启页面记忆时为空串） */
    var pageMemoryHint: String = ""

    /** 决策段产出的意图；执行段据此转译与执行 */
    var intent: AgentIntent? = null

    /** 意图是否来自端侧决策（影响对话角色与本地连续计数） */
    var fromLocal: Boolean = false

    /**
     * 上一步动作交付后抓到的快照（由 [StepRunner] 跨步传递，消费一次即清空）。
     * 观察段据此判定能否跳过完整观察（读屏 + 标注 + 截图）。
     */
    var pendingAfterObservation: AfterObservation? = null

    /**
     * 本步执行段在动作交付后抓到的快照，交给下一个 [StepSession]。
     * 只有真正改变页面、且已验证交付的动作才会写入。
     */
    var afterObservation: AfterObservation? = null
}
