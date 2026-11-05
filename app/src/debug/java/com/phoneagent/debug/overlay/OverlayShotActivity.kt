package com.phoneagent.debug.overlay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.phoneagent.feature.edge.EdgeLightingService
import com.phoneagent.overlay.FloatingWindowService
import com.phoneagent.overlay.OverlayDisplaySettings
import com.phoneagent.overlay.StepTextPlacement

/**
 * debug-only 浮层回归入口（不合并进 release 包）。
 *
 * 为什么要单独放一个 Activity：`FloatingWindowService` 是 `exported=false`，
 * adb 无法直接拉起；而 Android 12+ 禁止后台启动前台服务。
 * 用一个透明 Activity 在**前台**启动服务，再短暂停 1.2s 让窗口完成首帧，然后自己关掉，
 * 就能得到"桌面之上、只有浮层"的真实叠屏截图，且不依赖真实任务的 API Key。
 *
 * 用法：
 * adb shell am start -n com.phoneagent/.debug.overlay.OverlayShotActivity --es cmd t2
 *   cmd = t2（任务卡 + 长文案跑马灯） / done（完成卡片） / hide / restore
 *       / notice（普通降级提示细条） / notice-action（需操作细条）
 *       / stress（连续 20 次文案更新，验证跑马灯不重排）
 *       / edge-on（打开边缘光效并起标定预览） / edge-off（关闭，验证窗口被真正移除）
 *   可选 --es placement marquee|card|both（三档文字位置，写入正式设置后即时生效）
 */
class OverlayShotActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cmd = intent?.getStringExtra("cmd") ?: "t2"
        // 三档文字位置的走查入口：写的就是用户设置，服务监听后即时重绘
        intent?.getStringExtra("placement")?.let {
            OverlayDisplaySettings.setStepTextPlacement(this, StepTextPlacement.fromValue(it))
        }
        FloatingWindowService.start(this)
        Handler(Looper.getMainLooper()).postDelayed({
            when (cmd) {
                "t2" -> FloatingWindowService.update(
                    status = "正在核对当前页面的订单信息与收货地址",
                    task = "整理本月账单",
                    reasoning = "已读取订单列表，正在逐条核对金额与收货地址，确认无误后自动生成汇总表并保存到文件",
                    step = 3,
                    total = 6,
                    phase = "RUNNING",
                )
                "done" -> sendBroadcast(
                    Intent(DEBUG_DONE_ACTION)
                        .setPackage(packageName)
                        .putExtra("mode", "done")
                        .putExtra(
                            "message",
                            "已关闭蓝牙并回到桌面。共执行 3 步，用时 12 秒。\n" +
                                "设置 · 蓝牙：已关闭\n连接记录：仅保留耳机设备\n电池：78%，未开启省电模式",
                        ),
                )
                "hide", "restore" -> sendBroadcast(
                    Intent(DEBUG_DONE_ACTION).setPackage(packageName).putExtra("mode", cmd),
                )
                // 高频更新压测：连续 20 次不同文案，验证「不重排」的打点（HPA_PERF marquee-updates）
                "stress" -> {
                    repeat(20) { i ->
                        FloatingWindowService.update(
                            status = "第 ${i + 1} 次状态更新：正在核对第 ${i + 1} 条订单的金额与收货地址",
                            task = "整理本月账单",
                            reasoning = "已读取订单列表",
                            step = 3,
                            total = 6,
                            phase = "RUNNING",
                        )
                    }
                }
                // 边缘光效开关：关闭走的是"设置写入 → 服务监听 → removeOverlay"的真实链路
                "edge-on" -> {
                    OverlayDisplaySettings.setEdgeLightingEnabled(this, true)
                    EdgeLightingService.startPreview(this)
                }
                "edge-off" -> OverlayDisplaySettings.setEdgeLightingEnabled(this, false)
                // 降级提示细条：先起任务卡（载体），再发提示
                "notice", "notice-action" -> {
                    FloatingWindowService.update(
                        status = "正在打开闹钟",
                        task = "设置明早 7:30 闹钟",
                        reasoning = "已进入时钟页，准备写入闹钟",
                        step = 2,
                        total = 4,
                        phase = "RUNNING",
                    )
                    sendBroadcast(
                        Intent(DEBUG_DONE_ACTION).setPackage(packageName).putExtra("mode", cmd),
                    )
                }
            }
            finish()
        }, 1200)
    }

    companion object {
        private const val DEBUG_DONE_ACTION = "com.phoneagent.DEBUG_DONE"
    }
}
