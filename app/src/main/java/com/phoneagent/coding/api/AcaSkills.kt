package com.phoneagent.coding.api

import android.content.Context

/** 一条 ACA 技能的展示信息（实现仍在 `skill.AcaSkillInfo`） */
typealias AcaSkillSummary = com.phoneagent.coding.skill.AcaSkillInfo

/**
 * 主页技能列表的数据来源。
 *
 * 扫盘、随包 skill 落盘、manifest 解析都在切片内部；宿主只拿展示条目与坏包原因。
 */
object AcaSkills {

    /**
     * 流式扫描技能目录：每发现一个有效包回调一次 [onItem]。
     * 返回损坏包的「目录名 → 原因」，坏包只跳过不阻断。
     */
    suspend fun stream(
        context: Context,
        onItem: suspend (AcaSkillSummary) -> Unit,
    ): List<Pair<String, String>> =
        com.phoneagent.coding.skill.AcaSkillCatalog.streamScan(context, onItem)
}
