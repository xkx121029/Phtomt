package com.phoneagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.phoneagent.core.ai.ProviderPresets
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.device.provider.ProviderManager
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSize
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.appBorderStroke
import kotlinx.coroutines.launch

/**
 * 快速开始：把「跑起第一个任务」需要的三件事收在同一页。
 *
 * 冷启动的真实路径原先要翻三层：主页 → 设置主页 → 云端模型 → 回来找权限。
 * 这一页把它们合成一条线性流程——连模型、给无障碍、交代第一件事——
 * 每一项都在原地给出当前状态与下一步动作，失败原因也在这里就地解释。
 *
 * 只做**引导**，不做第二份表单：写进 [SettingsState] 的就是设置页那份真源，
 * 保存走同一条 [save]；端点与模型库的同步复用设置页的 ensure 函数，
 * 不另写一套"快速开始专用"的存储。
 */
@Composable
// 参数已按职责分组，合并成参数对象会牵动较多调用方，此处按规则豁免
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun SettingsQuickStart(
    vm: MainViewModel,
    st: SettingsState,
    save: () -> Unit,
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onStartTask: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val buzz = rememberHapticClick()
    val a11y by vm.a11yEnabled.collectAsState()

    // 进页刷一次无障碍状态：用户可能刚在系统设置里授权完返回
    LaunchedEffect(Unit) { vm.refreshA11yState(context) }

    var testing by remember { mutableStateOf(false) }
    var testHint by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }

    // 就绪口径与开屏引导共用同一个函数：一边只看 Key、一边还要求模型名会给出两种结论
    val modelReady = st.modelReady()

    /** 落库前把端点与模型库对齐，避免"模型能跑但模型库里找不到"的割裂状态 */
    fun commit() {
        commitModelConfig(st)
        save()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsScreenPad)
            .padding(LocalNavClearance.current),
    ) {
        SettingsPageHeader("快速开始", onBack, subtitle = "三步跑起第一个任务")
        Spacer(Modifier.height(AppSpacing.Md))

        QuickStartProgress(
            modelReady = modelReady,
            accessibilityReady = a11y,
        )

        Spacer(Modifier.height(AppSpacing.Lg))

        // ---------- ① 模型 ----------
        SettingsSection(
            title = "① 连接模型",
            subtitle = "选一个服务商，填入它控制台里的 API Key；地址与模型名已按预设填好",
            tone = SettingsTone.Brand,
        ) {
            Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                ) {
                    ProviderPresets.all.forEach { preset ->
                        FilterChip(
                            selected = st.baseUrl.trim().trimEnd('/') == preset.baseUrl.trimEnd('/'),
                            onClick = {
                                buzz()
                                applyProviderPreset(st, preset)
                                testHint = null
                            },
                            label = {
                                Text(
                                    preset.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }

                Spacer(Modifier.height(AppSpacing.Md))

                LabeledField("API Key") {
                    OutlinedTextField(
                        value = st.apiKey,
                        onValueChange = {
                            st.apiKey = it
                            testHint = null
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("粘贴服务商控制台里的 Key") },
                    )
                }
                Spacer(Modifier.height(AppSpacing.Sm))
                LabeledField("API 地址") {
                    OutlinedTextField(
                        value = st.baseUrl,
                        onValueChange = {
                            st.baseUrl = it
                            testHint = null
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(AppSpacing.Sm))
                LabeledField("模型名") {
                    OutlinedTextField(
                        value = st.model,
                        onValueChange = {
                            st.model = it
                            testHint = null
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(AppSpacing.Sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
                ) {
                    OutlinedButton(
                        enabled = modelReady && !testing,
                        onClick = {
                            buzz()
                            commit()
                            scope.launch {
                                testing = true
                                testHint = "正在连接…"
                                val result = vm.testConnection(
                                    st.baseUrl,
                                    st.apiKey,
                                    st.model,
                                    st.protocolOf(st.baseUrl),
                                )
                                testOk = result.isSuccess
                                testHint = if (result.isSuccess) {
                                    "连接成功，可以开始了"
                                } else {
                                    connectionFailureText(result.exceptionOrNull()?.message)
                                }
                                testing = false
                            }
                        },
                    ) {
                        Text(if (testing) "测试中…" else "测试连接")
                    }
                    TextButton(
                        enabled = modelReady,
                        onClick = {
                            buzz()
                            commit()
                            testHint = "已保存，稍后可以在「云端模型」里管理多个端点"
                            testOk = true
                        },
                    ) {
                        Text("保存")
                    }
                }

                testHint?.let { hint ->
                    Spacer(Modifier.height(AppSpacing.Xs))
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testOk) AppTheme.colors.onSurfaceRaised else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(Modifier.height(AppSpacing.Md))

        // ---------- ② 无障碍 ----------
        SettingsSection(
            title = "② 允许它操作屏幕",
            subtitle = "授权后才能读取界面、点击与滑动；不授权只能聊天，不能替你操作",
            tone = SettingsTone.Warm,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (a11y) AppIcons.CheckCircle else AppIcons.Guard,
                    contentDescription = null,
                    tint = if (a11y) MaterialTheme.colorScheme.primary else AppTheme.colors.onSurfaceRaised,
                    modifier = Modifier.size(AppSize.Icon),
                )
                Spacer(Modifier.size(AppSpacing.Md))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (a11y) "已授权" else "尚未授权",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.onSurfaceBase,
                    )
                    Text(
                        if (a11y) "现在可以替你操作手机" else "点右侧按钮，在系统里为它授权",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceRaised,
                    )
                }
                if (!a11y) {
                    TextButton(
                        onClick = {
                            buzz()
                            // 无障碍能力由独立的「服务组件」提供。没装组件时系统无障碍列表里
                            // 根本不会有本应用条目，直接跳过去就是死路；先把这个组件装上。
                            if (runCatching { ProviderManager.isInstalled(context) }.getOrDefault(false)) {
                                AgentAccessibilityService.openSettings(context)
                            } else {
                                ProviderManager.install(context)
                            }
                        },
                    ) {
                        Text("去开启")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = AppSpacing.Lg, end = AppSpacing.Lg, bottom = AppSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "截图与悬浮窗是可选能力，需要时再开",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceRaised,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { buzz(); onOpenPermissions() }) { Text("去设置") }
            }
        }

        Spacer(Modifier.height(AppSpacing.Md))

        // ---------- ③ 第一个任务 ----------
        SettingsSection(
            title = "③ 交代第一件事",
            subtitle = if (modelReady) {
                "挑一个例子直接试，也可以回到 Agent 页自己输入"
            } else {
                "先完成第 ① 步：没有模型时任务发不出去"
            },
            tone = SettingsTone.Cool,
        ) {
            Column(
                Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm),
            ) {
                FirstTasks.forEach { task ->
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(AppRadii.Item),
                        color = AppTheme.colors.surfaceRaised,
                        border = appBorderStroke(AppSize.Hairline, AppTheme.colors.outlineSoft),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AppSpacing.Md, vertical = AppSpacing.Md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                AppIcons.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(AppSize.IconSm),
                            )
                            Spacer(Modifier.size(AppSpacing.Sm))
                            Text(
                                task,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (modelReady) {
                                    AppTheme.colors.onSurfaceBase
                                } else {
                                    AppTheme.colors.onSurfaceRaised
                                },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = modelReady,
                                onClick = {
                                    buzz()
                                    commit()
                                    onStartTask(task)
                                },
                            ) {
                                Text("试试")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(AppSpacing.Xl))

        Button(
            enabled = modelReady,
            onClick = {
                buzz()
                commit()
                onStartTask(FirstTasks.first())
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存并开始第一个任务")
        }

        Spacer(Modifier.height(AppSpacing.Xxl))
    }
}

/** 顶部进度：三行只说"做没做"，不承担导航；状态变化即时可见 */
@Composable
private fun QuickStartProgress(modelReady: Boolean, accessibilityReady: Boolean) {
    val rows = listOf(
        "连接模型" to modelReady,
        "无障碍授权" to accessibilityReady,
        "发出第一个任务" to (modelReady && accessibilityReady),
    )
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Xs)) {
        rows.forEach { (label, done) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (done) AppIcons.CheckCircle else AppIcons.Info,
                    contentDescription = null,
                    tint = if (done) MaterialTheme.colorScheme.primary else AppTheme.colors.onSurfaceRaised,
                    modifier = Modifier.size(AppSize.IconSm),
                )
                Spacer(Modifier.size(AppSpacing.Sm))
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (done) AppTheme.colors.onSurfaceBase else AppTheme.colors.onSurfaceRaised,
                )
                Spacer(Modifier.size(AppSpacing.Xs))
                Text(
                    if (done) "就绪" else "待完成",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done) MaterialTheme.colorScheme.primary else AppTheme.colors.onSurfaceRaised,
                )
            }
        }
    }
}

/** 三个可直接执行的任务：覆盖操控、记录、联网三条典型路径，让用户第一次就看到它能干什么 */
internal val FirstTasks = listOf(
    "打开设置，把屏幕亮度调到一半",
    "在备忘录里记一条：明天十点开会",
    "用浏览器搜索附近的咖啡店",
)

/**
 * 连接失败的中文说法。
 *
 * 引擎抛出的异常文案是英文技术描述，直接弹给新用户等于没解释；
 * 这里按最常见的几类失败给出"下一步该做什么"，无法归类时保留原文兜底，
 * 不吞掉细节。
 */
internal fun connectionFailureText(raw: String?): String {
    val msg = raw?.trim().orEmpty()
    if (msg.isEmpty()) return "连接失败，请检查网络后重试"
    val lower = msg.lowercase()
    return when {
        lower.contains("401") || lower.contains("unauthorized") || lower.contains("invalid api key") ->
            "密钥无效或已过期，请到服务商控制台重新复制"
        lower.contains("403") ->
            "密钥没有该模型的权限，换个模型或开通对应服务"
        lower.contains("404") ->
            "接口地址或模型名不对（404），检查地址是否带 /v1 之类的路径"
        lower.contains("timeout") || lower.contains("timed out") ->
            "连接超时：检查网络，或确认地址在手机网络下可达"
        // 关掉网络时的典型报错：Unable to resolve host … / No address associated with hostname，
        // 归到「域名解析」而不是笼统的「连不上」，用户才知道该去开网络而不是改地址
        lower.contains("unable to resolve host") || lower.contains("no address associated with hostname") ->
            "域名解析失败：手机当前没有可用网络，连上网后重试"
        lower.contains("network is unreachable") || lower.contains("failed to connect to") ->
            "网络不可达：手机当前没有可用网络，或该地址只在内网可访问"
        lower.contains("unknownhost") || lower.contains("connect") ->
            "连不上这个地址：确认地址拼写正确，内网地址需要与手机同一网络"
        lower.contains("429") ->
            "请求太频繁或额度用尽，稍后再试"
        else -> "连接失败：$msg"
    }
}
