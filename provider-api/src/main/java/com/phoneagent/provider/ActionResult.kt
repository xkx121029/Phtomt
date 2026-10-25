package com.phoneagent.provider

import android.os.Parcel
import android.os.Parcelable

/**
 * AIDL 动作执行结果的统一回执。
 *
 * @param success 是否成功
 * @param reason 失败原因（中文，可直接展示/回注 AI）；成功时为空串
 * @param x/y 实际落点中心坐标（供主应用画光标）；无法取得时为 -1
 */
data class ActionResult(
    val success: Boolean,
    val reason: String = "",
    val x: Int = -1,
    val y: Int = -1,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readByte() != 0.toByte(),
        parcel.readString() ?: "",
        parcel.readInt(),
        parcel.readInt(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByte(if (success) 1 else 0)
        parcel.writeString(reason)
        parcel.writeInt(x)
        parcel.writeInt(y)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ActionResult> {
        override fun createFromParcel(parcel: Parcel): ActionResult = ActionResult(parcel)
        override fun newArray(size: Int): Array<ActionResult?> = arrayOfNulls(size)
    }
}
