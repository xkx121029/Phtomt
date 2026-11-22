package com.phoneagent.coding.api

import android.content.Context
import android.net.Uri

/**
 * 系统分享进来的文件交给编码切片。
 *
 * Activity 收到分享只做「选页 + 交接」，落盘细节（临时编码区、附件区状态）
 * 留在切片内部；这里给宿主一个入口，不暴露 `SharedFiles` 这个具体对象。
 */
object CodingShareIntake {

    /**
     * 把分享进来的 content:// 文件复制进临时编码区。
     *
     * 成功即结果可用（文件已落盘、附件已挂到编码页输入区），失败带可直接展示的中文原因。
     */
    suspend fun import(context: Context, uris: List<Uri>): Result<Unit> =
        com.phoneagent.coding.SharedFiles.import(context, uris).map { }
}
