package com.phoneagent.provider

import android.os.Parcel
import android.os.Parcelable

/**
 * shell 执行结果的 AIDL 回执（Shizuku / Termux / 无线 ADB 引导共用）。
 *
 * @param success 是否成功
 * @param output 成功时的合并输出
 * @param reason 失败原因（中文，可直接展示/回注 AI）
 * @param exitCode 退出码；与 shell 进程无关的失败（超时/未授权等）为 -1
 */
data class ShellResultParcel(
    val success: Boolean,
    val output: String = "",
    val reason: String = "",
    val exitCode: Int = 0,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readByte() != 0.toByte(),
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readInt(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByte(if (success) 1 else 0)
        parcel.writeString(output)
        parcel.writeString(reason)
        parcel.writeInt(exitCode)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ShellResultParcel> {
        override fun createFromParcel(parcel: Parcel): ShellResultParcel = ShellResultParcel(parcel)
        override fun newArray(size: Int): Array<ShellResultParcel?> = arrayOfNulls(size)

        /** 便捷构造：失败回执 */
        fun failure(reason: String, exitCode: Int = -1) = ShellResultParcel(false, "", reason, exitCode)

        /** 便捷构造：成功回执 */
        fun success(output: String = "") = ShellResultParcel(true, output, "", 0)
    }
}
