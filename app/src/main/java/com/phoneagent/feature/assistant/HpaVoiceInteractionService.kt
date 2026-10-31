package com.phoneagent.feature.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Intent
import android.view.View
import com.phoneagent.ui.MainActivity

/**
 * 数字助理入口三件套：注册为系统默认助理后，长按电源键/边缘手势唤起 HPA。
 *
 * - [HpaVoiceInteractionService]：助理服务入口（系统绑定）；
 * - [HpaVoiceInteractionSessionService]：会话服务（系统在每次唤起时实例化）；
 * - [HpaVoiceInteractionSession]：会话本体——收取 AssistStructure 屏幕上下文，
 *   暂存到 [AssistContextStore] 供引擎决策注入，然后把用户带回 Agent 页输入任务。
 *
 * 会话刻意不做自有 UI：输入与任务流都在 Agent 页里，助理唤起只是"带屏带电回来"的入口。
 * 收到的屏幕文本仅暂存内存、90 秒过期（见 [AssistContextStore]），不落盘。
 */
class HpaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // 占位：无需额外初始化；会话由 SessionService 按需创建
    }
}

class HpaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        HpaVoiceInteractionSession(this)
}

class HpaVoiceInteractionSession(service: VoiceInteractionSessionService) :
    VoiceInteractionSession(service) {


    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        structure?.let { AssistContextStore.put(extractText(it)) }
        // 带用户回 Agent 页：助理唤起的落点永远是输入框（电源键长按 → 说话/打字 → 开任务）
        // VoiceInteractionSession 不是 Context，统一经 applicationContext 发起
        val ctx = context.applicationContext
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            },
        )
        // 什么都不展示的透明会话没有存在意义，收掉自己，避免占住系统助理窗口
        finish()
    }

    /**
     * 摘取 AssistStructure 的可见文本（用户唤起时正看着的页面内容）。
     * 只取有文字的节点、跳过输入框中的密码类内容（AssistStructure 已自动置空，这里仅防重复），
     * 全程限深限条数，防止巨型页面把摘要撑爆。
     */
    private fun extractText(structure: AssistStructure): String = buildString {
        for (w in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(w).rootViewNode, 0, this)
        }
    }.replace(WS_RUN_RE, " ").trim()

    /** 深度优先摘文本：深度限 [MAX_TREE_DEPTH]、总长由 [AssistContextStore] 截断 */
    private fun walk(node: AssistStructure.ViewNode?, depth: Int, out: StringBuilder) {
        if (node == null || depth > MAX_TREE_DEPTH) return
        if (out.length > AssistContextStore.MAX_CHARS) return
        if (node.visibility == View.VISIBLE && node.contentDescription.isNullOrEmpty().not()) {
            out.append(node.contentDescription).append(' ')
        }
        node.text?.takeIf { it.isNotBlank() }?.let { out.append(it).append(' ') }
        for (i in 0 until node.childCount) walk(node.getChildAt(i), depth + 1, out)
    }

    companion object {
        /** AssistStructure 遍历深度上限：防巨型页面撑爆摘要 */
        private const val MAX_TREE_DEPTH = 40

        /** 空白归并正则：每次唤起助理都会走一次，预编译复用 */
        private val WS_RUN_RE = Regex("\\s+")
    }
}
