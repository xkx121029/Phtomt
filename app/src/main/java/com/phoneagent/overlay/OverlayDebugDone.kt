// 本文件里的数字多为设备指令码、位数与毫秒阈值，语义已在紧邻上下文标注，整文件豁免 MagicNumber
@file:Suppress("MagicNumber")
package com.phoneagent.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.phoneagent.BuildConfig
import com.phoneagent.domain.model.TaskOutcome
import com.phoneagent.domain.model.TaskStatus
import com.phoneagent.engine.runtime.TaskNotice
import com.phoneagent.engine.runtime.TaskNoticeCenter

internal fun FloatingWindowService.registerDebugDoneReceiver() {
    if (!BuildConfig.DEBUG) return
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra("mode")) {
                // 拉起空任务卡：量化「首次显示到首帧」
                "show" -> {
                    showFromUser()
                    updateNotification("任务执行中", "正在分析页面")
                }
                // 模拟截图前后隐藏/恢复：量化「隐藏与恢复 ≤0.3s」
                "hide" -> FloatingWindowService.setVisible(false)
                "restore" -> FloatingWindowService.setVisible(true)
                // debug 预览降级提示细条（普通 / 需操作两档），供截图与视觉走查
                "notice" -> TaskNoticeCenter.publish(
                    TaskNotice(
                        id = "debug:notice:info",
                        trigger = "channel_unavailable",
                        source = "launch",
                        text = intent.getStringExtra("text")
                            ?: "无障碍通道暂时不可用，已改用内置 Shizuku 继续执行。",
                        method = intent.getStringExtra("method") ?: "内置 Shizuku",
                        requiresAction = false,
                    ),
                )
                "notice-action" -> TaskNoticeCenter.publish(
                    TaskNotice(
                        id = "debug:notice:action",
                        trigger = "clock_fallback",
                        source = "clock",
                        text = intent.getStringExtra("text")
                            ?: "时钟页已打开，但无法自动写入闹钟，请你手动新建一个 07:30 的闹钟。",
                        method = null,
                        requiresAction = true,
                    ),
                )
                else -> showDone(
                    TaskOutcome(
                        // result 缺省按成功预览；要拍失败态就传 failed / aborted / not_achieved
                        status = when (intent.getStringExtra("result")) {
                            "failed" -> TaskStatus.FAILED
                            "aborted" -> TaskStatus.ABORTED
                            "not_achieved" -> TaskStatus.NOT_ACHIEVED
                            else -> TaskStatus.SUCCESS
                        },
                        summary = intent.getStringExtra("message")
                            ?: "已关闭蓝牙并回到桌面。共执行 3 步，用时 12 秒。",
                        elapsedMs = intent.getLongExtra("elapsed", 12_000L),
                        steps = intent.getIntExtra("steps", 3),
                        channel = intent.getStringExtra("channel") ?: "无障碍",
                        failureReason = intent.getStringExtra("reason").orEmpty(),
                    ),
                )
            }
        }
    }
    // debug-only 预览入口：不参与发布包，也不改变真实任务链路
    ContextCompat.registerReceiver(
        this,
        receiver,
        IntentFilter(FloatingWindowService.DEBUG_DONE_ACTION),
        ContextCompat.RECEIVER_EXPORTED,
    )
    debugDoneReceiver = receiver
}
