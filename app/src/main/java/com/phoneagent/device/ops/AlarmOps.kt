@file:Suppress("MagicNumber")
package com.phoneagent.device.ops

/**
 * 闹钟管理能力（`alarm_manage` 族）。
 *
 * 从 [DeviceOpsChannel] 拆出：这块只依赖注入通道（shell）与参数解析，不碰 `Context`，
 * 是整份文件里最容易独立的一块。拆开后「写后回查」这条安全约束也集中在一处：
 * 命令有回执不等于闹钟真的改了，必须按同一时分回查 Provider 才能报成功。
 */
internal class AlarmOps(private val shell: suspend (String) -> String?) {

    private val results = OpsResults

    /** 最近一次查询成功的闹钟 Provider URI：写/删沿用同一条，避免厂商机上 AOSP URI 不生效 */
    private var alarmUriInUse: String? = null

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    suspend fun run(op: String, args: Map<String, String>): OpsResult {
        val uriOut = queryAlarms()
            ?: return results.fail(
                "闹钟管理：读不到时钟应用的闹钟数据。系统没有开放闹钟数据库访问，" +
                    "需要 Root/Shizuku 的时钟 Provider；没有时可改走 open_app 打开时钟应用用手势操作，或重新 set_alarm 新增。",
            )
        if (op == "list") return results.ok("现有闹钟：\n$uriOut")
        val hour = OpsArgs.int(args, "hour")
        val minute = OpsArgs.int(args, "minute")
        if (hour == null || minute == null) {
            return results.fail(
                "alarm_manage 的 $op 需要 hour 与 minute 定位已有闹钟，例如 hour=7、minute=30；" +
                    "不确定现有闹钟时间时先调 alarm_manage（op=list）看一眼",
            )
        }
        val where = "hour=$hour AND minutes=$minute"
        return when (op) {
            "enable" -> toggle(where, hour, minute, args)
            "delete" -> delete(where, hour, minute)
            "update" -> update(where, args)
            else -> results.fail("alarm_manage 只支持 list/enable/delete/update")
        }
    }

    private suspend fun toggle(where: String, hour: Int, minute: Int, args: Map<String, String>): OpsResult {
        val enable = OpsArgs.bool(args, "enable")
            ?: return results.fail(
                "alarm_manage enable 需要 enable：true 表示启用这条闹钟，false 表示关闭，例如 enable=false",
            )
        val flag = if (enable) "1" else "0"
        return updateAlarm(where, listOf(Triple("enabled", "i", flag))).fold(
            {
                // 命令有回执不等于开关变了：回查同一时分的闹钟行
                val row = alarmRow(queryAlarms().orEmpty(), hour, minute)
                when {
                    row == null -> results.unconfirmed(
                        "已执行${if (enable) "启用" else "关闭"}，但回查不到 $hour:${"%02d".format(minute)} 的闹钟，" +
                            "无法确认是否生效。请打开时钟应用看一眼这条闹钟的开关",
                    )
                    row.contains("enabled=$flag") -> results.ok(
                        "已${if (enable) "启用" else "关闭"} $hour:${"%02d".format(minute)} 的闹钟（回查 enabled=$flag）",
                    )
                    else -> results.unconfirmed(
                        "闹钟开关未改动：回查 $hour:${"%02d".format(minute)} 仍是 ${Regex("enabled=(\\d)")
                            .find(row)?.groupValues?.get(1) ?: "未知"}。" +
                            "部分厂商时钟不开放该字段，请打开时钟应用手动切换",
                    )
                }
            },
            {
                results.fail(
                    "改闹钟开关失败：${it.message ?: "未知原因"}（需要 Root/Shizuku 且厂商时钟开放 Provider）。" +
                        "请到应用设置里确认注入通道已连接并授权，或打开时钟应用手动切换这条闹钟",
                )
            },
        )
    }

    private suspend fun delete(where: String, hour: Int, minute: Int): OpsResult {
        val out = shell("content delete --uri ${alarmUri()} --where ${ShellQuote.of(where)}")
        if (out.isNullOrBlank()) {
            return results.fail(
                "删除闹钟失败：注入通道不可用或时钟 Provider 拒绝。" +
                    "请到应用设置里确认 Shizuku/无线 ADB 已连接并授权后重试，" +
                    "或打开时钟应用手动删掉这条闹钟",
            )
        }
        // 删除命令有回执不等于行没了：回查一遍，还在就如实报失败
        val after = queryAlarms().orEmpty()
        return if (after.contains("hour=$hour") && after.contains("minutes=$minute")) {
            results.unconfirmed(
                "已执行删除，但回查时钟 Provider 仍能看到 $hour:${"%02d".format(minute)} 的闹钟。" +
                    "请打开时钟应用手动删除这条",
            )
        } else {
            results.ok("已删除 $hour:${"%02d".format(minute)} 的闹钟（回查已不存在；${out.take(200)}）")
        }
    }

    // 早退是为了让每个参数校验都紧贴自己的错误文案，硬拆成嵌套反而更难读
    @Suppress("ReturnCount")
    private suspend fun update(where: String, args: Map<String, String>): OpsResult {
        val nh = OpsArgs.int(args, "new_hour")
            ?: return results.fail("alarm_manage update 需要 new_hour：改后的小时（0~23），例如 new_hour=8")
        val nm = OpsArgs.int(args, "new_minute")
            ?: return results.fail("alarm_manage update 需要 new_minute：改后的分钟（0~59），例如 new_minute=15")
        return updateAlarm(where, listOf(Triple("hour", "i", nh.toString()), Triple("minutes", "i", nm.toString())))
            .fold(
                {
                    if (alarmRow(queryAlarms().orEmpty(), nh, nm) != null) {
                        results.ok("闹钟已改为 $nh:${"%02d".format(nm)}（回查新时间已存在）")
                    } else {
                        results.unconfirmed(
                            "改闹钟时间的命令已执行，但回查不到 $nh:${"%02d".format(nm)} 的闹钟，" +
                                "无法确认是否生效。请打开时钟应用看一眼列表",
                        )
                    }
                },
                {
                    results.fail(
                        "改闹钟时间失败：${it.message ?: "未知原因"}（需要 Root/Shizuku 且厂商时钟开放 Provider）。" +
                            "请到应用设置里确认注入通道已连接并授权，或打开时钟应用手动改时间",
                    )
                },
            )
    }

    // 分派函数多处早退以保证可读性，此处按规则豁免
    @Suppress("ReturnCount")
    private suspend fun updateAlarm(where: String, binds: List<Triple<String, String, String>>): Result<String> {
        val bind = binds.joinToString(" ") { (k, type, v) -> "--bind $k:$type:${ShellQuote.of(v)}" }
        val out = shell("content update --uri ${alarmUri()} --where ${ShellQuote.of(where)} $bind")
            ?: return Result.failure(IllegalStateException("注入通道不可用"))
        if (out.isBlank() || out.contains("0 rows", ignoreCase = true)) {
            return Result.failure(IllegalStateException("Provider 未匹配到该闹钟或拒绝写入"))
        }
        return Result.success(out.take(200))
    }

    /** 从闹钟 Provider 的输出里挑出「某时某分」那一行（写后回查用） */
    private fun alarmRow(query: String, hour: Int, minute: Int): String? =
        query.lineSequence()
            .firstOrNull { it.contains("hour=$hour") && it.contains("minutes=$minute") }
            ?.trim()

    /** 依次尝试常见时钟 Provider，返回第一条可用输出 */
    private suspend fun queryAlarms(): String? {
        for (uri in ALARM_URIS) {
            val out = shell("content query --uri $uri --projection _id:hour:minutes:enabled:label") ?: continue
            if (out.isNotBlank() && !out.contains("Error", ignoreCase = true) &&
                !out.contains("No result", ignoreCase = true)
            ) {
                alarmUriInUse = uri
                return out.take(2000)
            }
        }
        alarmUriInUse = null
        return null
    }

    /** 写/删闹钟用哪条 URI：优先用刚才查询成功的那条 */
    private fun alarmUri(): String = alarmUriInUse ?: ALARM_URI

    private companion object {
        /** 不同厂商时钟 Provider 的 authority（AOSP / OPPO / MIUI / 华为 / 荣耀 / Google） */
        private val ALARM_URIS = listOf(
            "content://com.android.deskclock/alarm",
            "content://com.oplus.alarmclock/alarm",
            "content://com.coloros.alarmclock/alarm",
            "content://com.miui.clock/alarm",
            "content://com.huawei.clock/alarm",
            "content://com.hihonor.clock/alarm",
            "content://com.google.android.deskclock/alarm",
        )

        /** 写入/删除时使用的 URI：先用 AOSP，失败再换厂商（由 content 命令报错暴露） */
        private const val ALARM_URI = "content://com.android.deskclock/alarm"
    }
}
