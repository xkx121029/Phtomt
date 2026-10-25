package com.phoneagent.provider

import android.os.Parcel
import android.os.Parcelable

/**
 * 活节点定位线索（跨进程版）。
 *
 * 与主应用 com.phoneagent.device.a11y.NodeSelector 字段一一对应：
 * 元素树快照里只有"值"，没有节点句柄，定位与执行都在 Provider 进程内完成。
 *
 * @param viewId 控件 view id 资源名（按后缀匹配）
 * @param label 控件可见文字 / 描述 / 派生标签
 * @param hintX/hintY 快照里控件的中心点（位置兜底与候选排序依据）；未知传 -1
 */
data class A11ySelector(
    val viewId: String? = null,
    val label: String? = null,
    val hintX: Int = -1,
    val hintY: Int = -1,
) : Parcelable {
    val hasHint: Boolean get() = hintX >= 0 && hintY >= 0

    constructor(parcel: Parcel) : this(
        parcel.readString(),
        parcel.readString(),
        parcel.readInt(),
        parcel.readInt(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(viewId)
        parcel.writeString(label)
        parcel.writeInt(hintX)
        parcel.writeInt(hintY)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<A11ySelector> {
        override fun createFromParcel(parcel: Parcel): A11ySelector = A11ySelector(parcel)
        override fun newArray(size: Int): Array<A11ySelector?> = arrayOfNulls(size)
    }
}
