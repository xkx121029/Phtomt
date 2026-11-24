package com.phoneagent.device.vision

/**
 * 视觉识别返回的一个控件（主程序侧的数据模型）。
 *
 * 说明：实际的"读图/OCR/框选"已在主程序进程内完成（3B 模型 / ML Kit OCR），
 * 由 [OnDeviceVision] 门面产出此模型，供展示与定位。
 *
 * 从 `device/vision/DetectedControl.kt` 里拆出来是因为 [StepTrace].boxes 用它，
 * 而 StepTrace 是 :data 的调试记录类型（StepTrace 还持有 Bitmap，纯 JVM 模块放不下）。
 * 同文件的 `ControlFormat`（画框/调色，用来渲染）留在了 :app。
 */
data class DetectedControl(
    /** 控件文字或描述 */
    val label: String,
    /** 控件类型：按钮/输入框/开关/标签页/文本/可滚动… */
    val role: String,
    /** 控件用途（中文，启发式推断，由端侧识别产出） */
    val purpose: String,
    /** 归一化边界 [left, top, right, bottom]（0~1） */
    val bounds: FloatArray,
    /** 中心点比例坐标 */
    val cx: Float,
    val cy: Float,
    /** 来源 */
    val source: String,
)
