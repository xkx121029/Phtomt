package com.phoneagent.device.provider

import com.phoneagent.provider.ProviderApi

/**
 * 服务组件与本应用之间的**接口契约判定**（纯逻辑，可单测）。
 *
 * 为什么不再比 versionName：版本号是给人看的，而且号段被回拨过（曾是 200 万段，后来回落到 9xxx 段）。
 * 按 versionName 排序判"谁更新"，结果是两头都错：
 * - 装着更新的组件 → 判成"已是最新"，可它缺的正是本应用要调的方法，调用被吞成 null；
 * - 装着更旧的组件 → 判断可能反着来，弹"需更新"而安装器拒降级，用户原地打转。
 *
 * 真正决定"能不能调通"的是 AIDL 契约版本（[ProviderApi.API_VERSION]）：接口增删方法它就递增。
 * 于是判据只有一条——**相等才兼容**，不相等时方向决定该怎么修：
 * - 组件旧：更新组件；
 * - 组件新：升级本应用（装回随包组件在系统侧就是降级，安装器会拒）。
 *
 * 版本号仍留作展示与排障用，不参与判定。
 */
internal object ProviderContract {

    /** 本应用编译时使用的契约版本（唯一来源：provider-api 的 AIDL 常量） */
    const val REQUIRED_API_VERSION: Int = ProviderApi.API_VERSION

    /** 兼容结论 */
    sealed class Verdict {
        /** 契约版本一致：可以正常调用 */
        data object Compatible : Verdict()

        /** 组件比本应用旧：缺接口，更新组件 */
        data class ProviderOutdated(val apiVersion: Int) : Verdict()

        /** 组件比本应用新：本应用需要升级 */
        data class AppOutdated(val apiVersion: Int) : Verdict()

        /** 读不到组件版本（还没绑上 / 调用失败）：不下结论 */
        data object Unknown : Verdict()
    }

    /**
     * @param installedApi 已绑定组件报告的 `apiVersion`；null = 还没拿到（未绑定或调用失败）
     * @param requiredApi 本应用需要的契约版本，默认取编译期常量（测试可显式传）
     */
    fun judge(installedApi: Int?, requiredApi: Int = REQUIRED_API_VERSION): Verdict = when {
        installedApi == null -> Verdict.Unknown
        installedApi == requiredApi -> Verdict.Compatible
        installedApi < requiredApi -> Verdict.ProviderOutdated(installedApi)
        else -> Verdict.AppOutdated(installedApi)
    }

    /**
     * 不兼容时给用户/AI 的**可操作**中文原因；兼容或未知返回 null。
     *
     * 调用方据此拒绝这次调用并把原因写进 lastError——宁可明确说"组件版本不对"，
     * 也不要让 binder 把缺方法的调用吞成 null，让人在"命令全废但没有红灯"里瞎猜。
     */
    fun mismatchReason(installedApi: Int?, requiredApi: Int = REQUIRED_API_VERSION): String? =
        when (val verdict = judge(installedApi, requiredApi)) {
            Verdict.Compatible, Verdict.Unknown -> null
            is Verdict.ProviderOutdated ->
                "服务组件接口版本过旧（组件 apiVersion=${verdict.apiVersion}，本应用需要 $requiredApi）：" +
                    "请到「设置 → 权限」更新服务组件后重试"
            is Verdict.AppOutdated ->
                "服务组件接口版本比本应用新（组件 apiVersion=${verdict.apiVersion}，本应用需要 $requiredApi）：" +
                    "需要升级本应用；装回随包组件在系统侧属于降级，安装器会拒绝"
        }
}
