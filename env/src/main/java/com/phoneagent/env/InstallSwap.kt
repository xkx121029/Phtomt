package com.phoneagent.env

import java.io.File

/**
 * 「临时目录解完再整体换入」的唯一实现。
 *
 * ## 为什么不能先删再解
 * 以前的顺序是「删掉目标目录 → 解压到目标目录」。这个顺序有两个致命窗口：
 * 解压中途被系统杀进程，或者解包本身失败，**已经装好的工具链就没了**；
 * 而重装/升级恰恰会在工具链已经存在时再走这一步。
 *
 * 现在的顺序：解到 `*.staging` → 现有内容改名为 `*.previous` → staging 就位 → 删 previous。
 * 换入的两个 rename 都在同一父目录里，是元数据操作，不需要复制整棵 100 MB 级的树。
 * 任何一步失败都保证 [dest] 仍是调用前的内容——用户可以继续用旧工具链，而不是对着
 * 一个「什么都没有」的 /opt 猜发生了什么。
 */
internal object InstallSwap {

    /** 失败时的原因会原样进用户可见的日志与步骤失败信息 */
    fun replace(staging: File, dest: File): String? {
        val previous = File(dest.parentFile, "${dest.name}.previous")
        deleteRecursive(previous)

        if (dest.exists() && !dest.renameTo(previous)) {
            return "原有内容无法让位（${dest.absolutePath}）"
        }
        if (!staging.renameTo(dest)) {
            if (previous.exists()) previous.renameTo(dest)
            return "新内容无法就位（${dest.absolutePath}），已回滚到原有内容"
        }
        deleteRecursive(previous)
        return null
    }

    fun deleteRecursive(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        file.delete()
    }
}
