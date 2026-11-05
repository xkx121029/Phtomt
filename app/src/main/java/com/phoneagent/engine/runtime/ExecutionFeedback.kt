package com.phoneagent.engine.runtime

import com.phoneagent.domain.model.ActionType
import com.phoneagent.domain.model.AgentAction

/**
 * 执行段回注文案与动作签名（重构阶段 B3）。
 *
 * 这里的两件事原本散在 runInner 800 行的执行分支里：
 * - 同签名动作熔断需要稳定的签名串（改一个分隔符就可能让熔断从此不触发）；
 * - 结构性错误要把「可用命令清单 / 换用无障碍动作」准确回注给 AI，文案本身就是护栏的一部分。
 * 抽成纯函数后可单测，主循环只保留「什么时候用」的判断。
 */
internal object ExecutionFeedback {

    /** 同签名动作的签名串：类型 + 目标 + 坐标 + 键值 + 命令，缺省字段按空串参与拼接 */
    fun actionSignature(action: AgentAction): String = buildString {
        append(action.type)
        append('|').append(action.target?.value.orEmpty())
        append('|').append(action.x).append(',').append(action.y)
        append('|').append(action.keycode.orEmpty()).append('|').append(action.command.orEmpty())
    }

    /**
     * 结构性错误（未知命令/参数无效）回注给 AI 的纠正提示。
     * shell 连续无效 ≥2 次时升级为「禁止再用 shell，改用无障碍动作」；其它意图给出可用意图清单。
     */
    // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
    @Suppress("MaxLineLength")
    fun structuralErrorHint(actionType: String, reason: String, invalidCommandStreak: Int): String =
        if (actionType == ActionType.SHELL) {
            if (invalidCommandStreak >= 2) {
                "\n\n你已经连续 $invalidCommandStreak 次发出不存在的 shell 命令（$reason）。" +
                    "从这一步起【禁止再使用 type=\"shell\"】。改用无障碍动作 type=tap/long_press/type/scroll_to/launch + target.id 或 target.label（不需坐标，执行层会自动定位）。" +
                    "只有显式列在表格里的命令才存在，其它一律不允许：tap/lp/dt/sw/su/sd/sl/sr/key/back/home/recents/text/am/stop/dump/launch/brightness/screenshot/info/wifi_on/wifi_off/clip。不要编造、不要臆测命令。"
            } else {
                "\n\n你发出的 shell 命令无效：$reason\n请只使用以下友好命令：tap/lp/dt/sw/su/sd/sl/sr/key/back/home/recents/text/am/stop/dump/launch/brightness/screenshot/info/wifi_on/wifi_off/clip。坐标用比例（0~1）或像素。不要编造不存在的命令。"
            }
        } else {
            "\n\n意图「$actionType」执行无效：$reason\n请重新决策，使用 open_app/open/tap/long_press/input/swipe/press/wait/scroll_to/write_doc/browse_open/browse_read/browse_click/browse_input/browse_scroll/browse_back/finish/give_up 中正确的意图与参数。目标优先用 by_id/by_text，找不到用 by_hint；网页内的元素改用 browse_click + {\"by\":\"text\",\"value\":\"元素文字\"}。"
        }
}
