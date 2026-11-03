package com.phoneagent.engine.execution

import com.phoneagent.domain.model.A11yEndpoint
import com.phoneagent.domain.model.A11yEndpoints
import com.phoneagent.domain.model.CapabilityRegistry
import com.phoneagent.domain.model.IntentType
import com.phoneagent.domain.model.RiskTier

/**
 * 动作模式（权限档位）：决定"AI 允许提出哪一类请求"。
 *
 * 与 [CapabilityManager.Mode]（通道模式：SHIZUKU / ACCESSIBILITY / READONLY）**正交**：
 * - 通道模式回答"能不能执行"（设备有没有相应权限）；
 * - 动作模式回答"允不允许 AI 提出这类请求"（用户给 AI 的授权范围）。
 *
 * 两者叠加生效：例如通道模式为 READONLY 时任何模式都不能自动执行；
 * 动作模式为保守时，即使有 Shizuku 权限也不允许 AI 点击/输入。
 */
enum class ActionMode(
    val key: String,
    val label: String,
    val labelEn: String,
    val summary: String,
    val summaryEn: String,
) {
    /** 保守：仅低风险命令（只读 + 导航 + 纯本地动作），不放行任何改动设备/外部状态的动作 */
    CONSERVATIVE(
        "CONSERVATIVE", "保守", "Conservative",
        "仅低风险命令：读取信息 / 导航 / 本地读写",
        "low-risk commands only: information reading / navigation / local read-write",
    ),

    /** 均衡：转译层全部意图可用（= 既有默认行为） */
    BALANCED(
        "BALANCED", "均衡", "Balanced",
        "转译层全部意图可用",
        "all translator intents available",
    ),

    /** 自由：均衡 + AI 自写 shizuku/adb/termux 命令 + 调用全量无障碍端点 */
    FREE(
        "FREE", "自由", "Free",
        "全部意图 + AI 自写命令 + 全量无障碍端点",
        "all intents + self-written commands + all accessibility endpoints",
    );

    companion object {
        /** 默认档位：均衡即既有行为，保证升级后零行为变化 */
        val DEFAULT = BALANCED

        /** 宽松解析持久化的档位字符串；非法/空值一律回落到 [DEFAULT]（避免脏数据把权限意外放大或收紧） */
        fun fromKey(raw: String?): ActionMode =
            entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * 动作模式策略：三档权限的**唯一定义点**。
 *
 * 风险分级不再在这里手写：三档集合直接取自声明式能力注册表
 * （见 [com.phoneagent.domain.model.CapabilityRegistry]），端侧门控与提示词共用同一份数据，
 * 杜绝"提示词说能用、端侧其实拒了"或反过来的漂移（与 [com.phoneagent.feature.browser.BrowserGuard] 同思路）。
 */
object ActionPolicy {

    /** 放行判定结果 */
    sealed class Verdict {
        /** 放行 */
        data object Allowed : Verdict()
        /** 拒绝：reason 为给 AI 看的中文人话原因（含换档建议） */
        data class Denied(val reason: String) : Verdict()
    }

    /**
     * 低风险意图（23 项）：不改变设备/外部状态，或只做本地读写与只读浏览。
     * 保守模式只放行这一档。
     */
    val lowRisk: Set<String> = CapabilityRegistry.ofRisk(RiskTier.LOW)

    /**
     * 中风险意图（16 项）：会改动设备/外部状态，但可见、可撤销，或本就是用户明确要求。
     * 均衡模式起放行。
     */
    val midRisk: Set<String> = CapabilityRegistry.ofRisk(RiskTier.MID)

    /**
     * 高风险意图（3 项）：真实不可逆（发送/授权结算/删除）。
     * 保守模式拒绝；均衡/自由放行但沿用既有 needs_confirmation 机制由 AI 自行标注。
     */
    val highRisk: Set<String> = CapabilityRegistry.ofRisk(RiskTier.HIGH)

    /**
     * 自由模式专属意图（2 项）：不属于转译层既有命令，保守/均衡一律拒绝。
     * - SHELL：AI 自写 shizuku/adb/termux 命令
     * - A11Y：直接调用无障碍端点
     */
    val freeOnly: Set<String> = CapabilityRegistry.ofRisk(RiskTier.EXTENDED)

    /** 已标注风险档的意图全集（= 低 + 中 + 高） */
    val tiered: Set<String> = lowRisk + midRisk + highRisk

    /**
     * 无障碍端点白名单（18 项）：自由模式下 AI 可调用的底层接口全集。
     * 唯一定义点在 [A11yEndpoints.all]（domain 层，与 a11y 能力的参数声明同源），这里只做转发。
     */
    @Suppress("MemberVisibilityCanBePrivate")
    val a11yEndpoints: List<A11yEndpoint> = A11yEndpoints.all

    /** 按端点名查白名单条目；未知端名返回 null（调用方据此失败并回报可选清单） */
    fun endpointOf(name: String?): A11yEndpoint? = A11yEndpoints.of(name)

    /**
     * 判定当前档位是否放行该意图。
     *
     * 未知意图（不在任何档位、也不是自由专属）一律放行——交给转译层按"未知意图"失败处理，
     * 这里不越权拦截，避免两条链路各说一套。
     */
    fun allows(mode: ActionMode, intent: String?): Verdict {
        val name = intent?.trim().orEmpty()
        if (name.isEmpty()) return Verdict.Allowed
        return when (mode) {
            ActionMode.FREE -> Verdict.Allowed
            ActionMode.BALANCED -> if (name in freeOnly) Verdict.Denied(freeModeOnlyReason(name)) else Verdict.Allowed
            ActionMode.CONSERVATIVE -> when {
                name in lowRisk -> Verdict.Allowed
                name in freeOnly -> Verdict.Denied(freeModeOnlyReason(name))
                name in tiered -> Verdict.Denied(conservativeReason(name))
                // 认不出的意图不在这里拦：转译层会以"未知意图"明确失败，
                // 门控抢着报"越权"只会把拼错的意图名说成权限问题，误导 AI 去换档
                else -> Verdict.Allowed
            }
        }
    }

    private fun freeModeOnlyReason(intent: String): String =
        "意图「$intent」超出当前动作模式的授权范围，此操作需要更改模式。端侧已拒绝，未对设备做任何操作。" +
            "请改用当前可用的操作推进；确实必须用到它时，用 say 提示用户更改动作模式（Agent 页输入栏下方）。"

    private fun conservativeReason(intent: String): String =
        "意图「$intent」超出当前动作模式（保守）的授权范围，此操作需要更改模式。端侧已拒绝，未对设备做任何操作。" +
            "当前模式可用：${lowRisk.joinToString("/")}。" +
            "请改用上列意图推进任务；确实必须用到被拒操作时，用 say 提示用户更改动作模式。"

    /**
     * 覆盖度自检：返回"已标注风险档但不在 [IntentType.ALL] 里"的意图（多余项）。
     * 与 [missingTiers] 一起供单测断言，防止新增意图时漏标注。
     */
    fun extraTiers(): Set<String> = tiered - IntentType.ALL

    /** 覆盖度自检：返回"[IntentType.ALL] 里未标注风险档、也非自由专属"的意图（漏标注项） */
    fun missingTiers(): Set<String> = IntentType.ALL - tiered - freeOnly
}