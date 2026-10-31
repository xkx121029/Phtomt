package com.phoneagent.feature.entry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phoneagent.core.log.AppLog
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.overlay.AgentStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatformTools

/**
 * 对外控制接口（广播契约）：Tasker / MacroDroid / 自动化工具 / coda 等第三方
 * 用标准广播驱动 HPA——下发任务、停止任务、查询状态。
 *
 * 安全模型：接收器 exported，**任何应用都能发广播**，因此双闸门：
 * 1. 设置页「对外控制」总开关（默认关闭）；
 * 2. 口令校验：广播 extra `token` 与设置里的口令全等才放行。
 *    口令必须达到 [MIN_TOKEN_LENGTH] 位——空口令等于没有闸门，
 *    任何知道 action 名的应用都能下发任务、停止任务，因此这里硬性拒绝（设置页同口径提示）。
 * 未授权一律静默拒绝（不 setResult 错误详情给陌生调用方），只在日志留痕。
 *
 * 广播契约（对外保持稳定，勿改字段名）：
 * - `com.phoneagent.action.EXTERNAL_RUN_TASK`：extra `text`（任务全文）+ `token`；运行中自动排队。
 * - `com.phoneagent.action.EXTERNAL_STOP_TASK`：extra `token`。
 * - `com.phoneagent.action.EXTERNAL_QUERY_STATUS`：ordered broadcast，setResultData 返回 JSON
 *   `{"ok":true,"running":..,"task":..,"phase":..,"step":..,"status":..}`。
 */
class TaskControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        // runCatching 兜底：对外广播内容不可信，任何解析/分发异常都不能崩溃应用
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { handle(intent) }.onFailure {
                AppLog.w(TAG, "对外控制处理失败：${it.message}")
            }
            result.finish()
        }
    }

    private suspend fun handle(intent: Intent) {
        val koin = KoinPlatformTools.defaultContext().get()
        val settings = koin.get<AppSettings>().settings.first()
        val reject = rejectReason(settings, intent)
        if (reject != null) {
            AppLog.i(TAG, reject)
            return
        }
        val engine = koin.get<com.phoneagent.engine.AgentEngine>()
        when (intent.action) {
            TaskEntry.ACTION_EXTERNAL_RUN -> engine.start(taskTextOf(intent) ?: return)
            TaskEntry.ACTION_EXTERNAL_STOP -> engine.stop()
            TaskEntry.ACTION_EXTERNAL_STATUS -> reportStatus()
        }
    }

    /** 授权判定收口：返回拒绝原因（供日志）；放行返回 null */
    private fun rejectReason(settings: AppSettings.Settings, intent: Intent): String? = when {
        !settings.externalControlEnabled -> "拒绝外部请求（对外控制未开启）：${intent.action}"
        // 口令过短等同没有闸门：exported 接收器上任何应用都能猜 action 名来下发任务，
        // 这里硬性拒绝而不是"仅靠开关"，避免用户以为设了开关就安全了
        settings.externalControlToken.length < MIN_TOKEN_LENGTH ->
            "拒绝外部请求（口令未设置或少于 $MIN_TOKEN_LENGTH 位）：${intent.action}"

        !tokenEquals(intent.getStringExtra(EXTRA_TOKEN), settings.externalControlToken) ->
            "拒绝外部请求（口令不符）：${intent.action}"

        else -> null
    }

    /**
     * 口令比较：逐字节比较且不提前返回。
     *
     * 普通 `==` 在首个不同字符处返回，理论上可被计时侧信道逐位试探；
     * 虽然广播本身有系统开销、时序噪声很大，但比较是零成本的，没有理由留这个口子。
     */
    private fun tokenEquals(input: String?, expected: String): Boolean {
        // 长度不同直接判否（长度本身不是秘密）；null 与空串同理
        if (input == null || input.length != expected.length) return false
        var diff = 0
        for (i in input.indices) diff = diff or (input[i].code xor expected[i].code)
        return diff == 0
    }

    /** 任务文本：内部契约 EXTRA_TASK_TEXT 优先，外部习惯的 text 兼容 */
    private fun taskTextOf(intent: Intent): String? =
        (intent.getStringExtra(TaskEntry.EXTRA_TASK_TEXT) ?: intent.getStringExtra(EXTRA_TEXT_ALIAS))
            ?.trim()?.takeIf { it.isNotEmpty() }

    /** ordered 广播回传状态 JSON（给 Tasker 等做条件判断） */
    private fun reportStatus() {
        if (!isOrderedBroadcast) return
        val s = AgentStatusStore.snapshot.value
        setResultData(
            kotlinx.serialization.json.buildJsonObject {
                put("ok", kotlinx.serialization.json.JsonPrimitive(true))
                put("running", kotlinx.serialization.json.JsonPrimitive(!s.done && s.status != null))
                put("task", kotlinx.serialization.json.JsonPrimitive(s.task ?: ""))
                put("phase", kotlinx.serialization.json.JsonPrimitive(s.phase ?: ""))
                put("step", kotlinx.serialization.json.JsonPrimitive(s.step))
                put("status", kotlinx.serialization.json.JsonPrimitive(s.status ?: ""))
            }.toString(),
        )
    }

    companion object {
        private const val TAG = "TaskControlReceiver"

        /**
         * 口令最小长度：短于此长度一律拒绝。
         *
         * exported 接收器等于对全机应用开放入口，空口令/4 位口令实际上等于没有闸门；
         * 8 位是"随手设一个也不至于被秒猜"的下限，设置页用同一常量做提示。
         */
        const val MIN_TOKEN_LENGTH = 8

        /** 口令 extra（对外契约） */
        const val EXTRA_TOKEN = "token"

        /** 任务文本别名：外部习惯用 text，内部契约用 EXTRA_TASK_TEXT，两者都收 */
        const val EXTRA_TEXT_ALIAS = "text"
    }
}
