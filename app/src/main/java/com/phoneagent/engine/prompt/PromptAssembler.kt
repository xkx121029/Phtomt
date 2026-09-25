package com.phoneagent.engine.prompt

import com.phoneagent.engine.PromptLang

/**
 * 一次装配的上下文：语言 + 条件标志位 + 占位符变量。
 *
 * [flags] 由调用点按"此刻真实成立的事实"构造（例如真的带了截图才置 [PromptFlag.HAS_VISION]），
 * 装配器只做纯粹的集合判定，不反过来去猜业务状态。
 */
data class PromptContext(
    val lang: PromptLang,
    val flags: Set<PromptFlag> = emptySet(),
    val vars: PromptVars = PromptVars(),
)

/**
 * 提示词装配器：动态提示词系统的执行核心。
 *
 * 职责有三，且**只有这三件**：
 * 1. **选块**：区块的 [PromptBlock.requires] 全部命中、[PromptBlock.excludes] 全部不命中才在场；
 * 2. **拼接**：按 order 排序后逐块 `prefix + body + sep` 首尾相接，块间不插任何额外字符；
 * 3. **渲染**：整组一次性替换 `{占位符}`（见 [PromptTemplateEngine.renderOnce]）。
 *
 * 之所以"整组一次渲染"而不是"逐块渲染再拼接"：区块正文里的 `{by: id|text|hint}`、`{"intent":...}`
 * 这类花括号不是占位符，单趟替换天然避开它们；而用户文本里若恰好写了 `{task}`，也不会被二次展开。
 *
 * 等价性保证：全部区块在场时，[assemble] 的输出与重构前的原文**逐字节相等**
 * （由 PromptSnapshotTest 的金样本锁定）。因此本类的任何改动都必须先让那份金样本继续通过。
 */
object PromptAssembler {

    /** 某组的区块清单（按 order 排序，order 相同按声明顺序，结果确定） */
    fun blocks(group: PromptGroup): List<PromptBlock> =
        PromptCatalog.of(group).sortedBy { it.order }

    /** 某组在给定上下文中**在场**的区块 */
    fun select(group: PromptGroup, ctx: PromptContext): List<PromptBlock> =
        blocks(group).filter { it.isPresent(ctx.flags) }

    /**
     * 装配某组的提示词文本。
     *
     * 空组返回空串（不是 null）：调用点拿到的就是"这一组该注入的文字"。
     */
    fun assemble(group: PromptGroup, ctx: PromptContext): String {
        val selected = select(group, ctx)
        if (selected.isEmpty()) return ""
        val joined = buildString {
            selected.forEach { block ->
                append(block.prefix)
                append(if (ctx.lang == PromptLang.CN) block.bodyCN else block.bodyEN)
                append(block.sep)
            }
        }
        return PromptTemplateEngine.renderOnce(joined, ctx.vars.map)
    }

    /**
     * 装配审计：返回"在场/缺席 + 原因"的逐块说明。
     *
     * 只服务于排障与单测（例如断言"安全块在任意标志位组合下恒在场"），
     * 不参与运行期提示词，也不对用户展示。
     */
    fun plan(group: PromptGroup, ctx: PromptContext): List<String> =
        blocks(group).map { block ->
            val why = when {
                block.isPresent(ctx.flags) ->
                    if (block.requires.isEmpty()) "在场（无条件）" else "在场（命中 ${block.requires.joinToString("+")}）"
                else -> "缺席（缺 ${(block.requires - ctx.flags).joinToString("+")}" +
                    (if (block.excludes.any { it in ctx.flags }) "；命中排除 ${block.excludes.filter { it in ctx.flags }.joinToString("+")}" else "") + "）"
            }
            "${block.id}\t$why"
        }

    /**
     * 判定单块是否在场。
     *
     * 放在这里而不是 [PromptBlock] 上，是为了让"到场规则"只有一个定义点：改规则只改这一个函数。
     */
    private fun PromptBlock.isPresent(flags: Set<PromptFlag>): Boolean =
        flags.containsAll(requires) && excludes.none { it in flags }

    /**
     * 裁剪自检：列出"因为条件不满足而被裁掉"且**被标为安全块**的区块。
     *
     * 安全块（铁律 / 授权范围 / 禁止输出 / 能力声明）不应被任何条件裁掉，
     * 返回非空即说明目录里给安全块挂了 requires —— 由单测断言恒为空。
     */
    fun unsafeCrops(ctx: PromptContext): List<String> =
        PromptCatalog.all
            .filter { it.safe && !it.isPresent(ctx.flags) }
            .map { "${it.id}（缺 ${it.requires.joinToString("+")}）" }
}