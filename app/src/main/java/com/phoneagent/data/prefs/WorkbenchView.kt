package com.phoneagent.data.prefs

/**
 * 工作台风格下的两种工作视图（[AppSettings.Settings.workbenchView] 的取值）。
 *
 * 与 [AgentSurface] 同一套"存字符串、读取归一"的做法：脏数据回落 Agent，
 * 视图切换本身由界面即时落盘，下次进入同一套风格时保持上次所在视图。
 */
object WorkbenchView {
    /** 操控 / 对话统一视图：接任务、看执行轨迹 */
    const val AGENT = "agent"
    /** 编码工作台视图：工作区、会话与工具调用 */
    const val CODING = "coding"

    fun normalize(raw: String?): String = if (raw == CODING) CODING else AGENT
}
