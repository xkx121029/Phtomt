// ---------------------------------------------------------------------------
// 覆盖率闸门**失败时**的逐类诊断 —— 六条闸门（`:app` / `:data` / `:domain` /
// `:core:text` / `:core:security` / `:ui:design`）共用这一份实现。
//
// ## 为什么要有它
//
// 闸门原本失败时只有一句「只剩 1350 行可执行代码（下限 1351）：包里的类被搬走了？」。
// 它证明了「少了东西」，但说不出**少了谁、差几行、下限该写成多少**。
// 2026-11-25 CI 就是这样红的：`AgentSurface` 下沉到 `:domain` 带走 1 行，
// 而搬类的人和查 CI 的人都得自己翻 JaCoCo 报告，才能把 1351 这个数字减对。
//
// ## 为什么写成「挂到 extra 上的函数值」
//
// Kotlin DSL 的 `apply(from = ...)` 只执行脚本，不会把它内部定义的函数带进应用方的作用域；
// 但函数值可以存进 extra，模块侧按函数类型取用。这样六条闸门仍然只有一份实现——
// 复制六遍的下场是口径漂移（改了一处排序，另外五处不会跟着改）。
//
// ## 边界
//
// 判据一个字都不改：这个函数只在闸门**已经判定失败**之后被调用，通过路径不打印任何额外内容。
// **合计口径不在这里重算**：包级 total 由闸门自己算好了传进来。原因是 JaCoCo 的类级 LINE
// 计数器是「按类」计的，同一源码行生成了多个类（lambda / Companion）时会被重复计，
// 类级求和会大于包级 total —— 在这里另算一个总数只会和闸门那句话打架。
// ---------------------------------------------------------------------------

val coverageShame: (String, (String) -> Boolean, Int, Int?, String) -> String = shame@{
        xmlText: String,
        select: (String) -> Boolean,
        measuredTotal: Int,
        minLines: Int?,
        knob: String,
    ->
    // 逐类取 LINE 计数器。没有可执行行的类（接口 / 常量）不列。
    val rows = Regex("""<class name="([^"]+)"[^>]*>([\s\S]*?)</class>""").findAll(xmlText).mapNotNull { cls ->
        val name = cls.groupValues[1]
        if (!select(name)) return@mapNotNull null
        val counter = Regex("""<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""")
            .findAll(cls.groupValues[2]).lastOrNull() ?: return@mapNotNull null
        val missed = counter.groupValues[1].toInt()
        val covered = counter.groupValues[2].toInt()
        Triple(name.substringAfterLast('/'), missed + covered, covered)
    }.filter { it.second > 0 }.sortedByDescending { it.second }.toList()

    val out = StringBuilder()
    if (rows.isEmpty()) {
        out.append("  （报告里没有任何带可执行行的类）")
    } else {
        out.append("  逐类行数（按类计，同一源码行生成多个类时会重复计，因此本清单合计大于上面的 $measuredTotal 行）：")
        // 只列前 20 个：剩下的多是自动生成的一行 lambda / Companion，全列出来没人看。
        val shown = rows.take(20)
        shown.forEach { (name, lines, cov) ->
            val pct = "%.1f%%".format(cov * 100.0 / lines)
            out.append("\n    %-46s 行 %5d  covered %5d  %6s".format(name, lines, cov, pct))
        }
        if (rows.size > shown.size) {
            val rest = rows.drop(shown.size)
            out.append("\n    …另有 ${rest.size} 个类（每个 ≤ ${rest.first().second} 行）")
        }
    }

    if (minLines != null && measuredTotal < minLines) {
        out.append("\n  建议：把 $knob 改成 $measuredTotal（本组实测值）。")
        out.append("若上面某个类确实已搬到别的模块，请把它从本模块的规则清单里删掉，")
        out.append("并把红线一起搬进那个模块自己的闸门——而不是留在这里改测剩下的类。")
    } else {
        out.append("\n  建议：核对上面这个清单，确认是否有类被搬走或改名；")
        out.append("若只是覆盖率掉了，请补测而不是下调 $knob。")
    }
    out.toString()
}

extra["coverageShame"] = coverageShame
