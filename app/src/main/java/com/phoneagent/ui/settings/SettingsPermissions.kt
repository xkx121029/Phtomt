package com.phoneagent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.device.provider.ProviderManager
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.LocalSnackbar
import com.phoneagent.ui.components.SnackbarType
import com.phoneagent.ui.home.PermissionRadar
import com.phoneagent.ui.theme.Success
import kotlinx.coroutines.launch

/**
 * 权限与执行通道页：把散在主页/技能页的内容收拢到设置里，并接管原「Agent 运行」页里的
 * 执行通道优先级开关——它与本页的配对界面说的是同一件事，放两处只会互相打架。
 *
 * 只做聚合，不重写实现——权限雷达复用主页的 [PermissionRadar]；
 * 执行通道展示内置 Shizuku（一键启动 + 首次引导）与 Termux（[ShizukuChannelCard] / [TermuxChannelCard]）。
 * 「无线ADB」独立通道已砍：无线 ADB 只是内置 Shizuku server 的启动引导机制，不再单独暴露。
 */
@Composable
internal fun SettingsPermissions(
    vm: MainViewModel,
    st: SettingsState,
    save: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val permissions by vm.permissions.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshPermissions(context)
        vm.refreshStatus(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsScreenPad)
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            .padding(LocalNavClearance.current),
    ) {
        // 骨架节奏与全设置流程一致：页头后 20 / 分区之间 16 / 结尾 28。
        // 原先这里套了两层 Column，Shizuku/Termux 卡放在无横向留白的外层，
        // 铺满到屏幕边缘，与其他页的 20dp 栅格对不上——已拍平收进同一层。
        SettingsPageHeader(
            title = "权限与执行通道",
            onBack = onBack,
            subtitle = "授予系统权限，配置 shell 执行通路",
        )
        Spacer(Modifier.height(20.dp))

        // 服务组件：无障碍 / 内置 Shizuku / Termux 三条通道都运行在独立应用
        // com.phoneagent.provider 里（随包附带的 APK，经 AIDL 调用），
        // 主应用升级/重启时它不会被一起杀掉，无障碍因此不会掉线。
        // 安装需用户在系统安装器里确认，回到前台必须重读状态，所以挂在 ON_RESUME 上刷新。
        SettingsSection(
            title = "服务组件",
            subtitle = "无障碍、内置 Shizuku、Termux 的运行载体",
            tone = SettingsTone.Warm,
        ) {
            val lifecycleOwner = LocalLifecycleOwner.current
            var installedVersion by remember { mutableStateOf(ProviderManager.installedVersionName(context)) }
            var bundledVersion by remember { mutableStateOf(ProviderManager.bundledVersion(context)) }
            var needsInstall by remember { mutableStateOf(ProviderManager.needsInstall(context)) }
            var a11yEnabled by remember { mutableStateOf(AgentAccessibilityService.isServiceEnabled(context)) }
            // 真实的通道状态：只显示「已安装」会让用户误以为能用（安装 ≠ 绑定 ≠ 无障碍已开启）
            val channelConnected by ProviderManager.connected.collectAsState()
            val channelError by ProviderManager.lastError.collectAsState()
            // 自检：把「装 / 开 / 绑 / 读树 / 截图」拆成五步实测，结果留在页面上
            var selfTestRunning by remember { mutableStateOf(false) }
            var selfTestSteps by remember { mutableStateOf<List<ProviderManager.SelfTestStep>?>(null) }
            // ---- 看门狗：配置与状态都持久化在 Provider 侧，经 AIDL 读写（两应用不共享 prefs）----
            val scope = rememberCoroutineScope()
            var watchdog by remember { mutableStateOf<WatchdogUi?>(null) }
            var watchdogBusy by remember { mutableStateOf(false) }

            /** 读一次看门狗状态快照；Provider 未连接返回 null（保持现值，不闪变） */
            suspend fun readWatchdog(): WatchdogUi? =
                ProviderManager.call(context, 3_000) { it.watchdogStatus() }?.let(::parseWatchdogStatus)

            // 从系统安装器/无障碍设置页返回时窗口回前台，立刻重读三项状态与看门狗快照
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        installedVersion = ProviderManager.installedVersionName(context)
                        bundledVersion = ProviderManager.bundledVersion(context)
                        needsInstall = ProviderManager.needsInstall(context)
                        a11yEnabled = AgentAccessibilityService.isServiceEnabled(context)
                        if (installedVersion != null) scope.launch { watchdog = readWatchdog() ?: watchdog }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            val snackbar = LocalSnackbar.current

            /** 乐观更新后写回 Provider；失败回滚并提示，成功后重读快照拿 running/canRepairA11y 真值 */
            fun applyWatchdog(target: WatchdogUi) {
                if (watchdogBusy) return
                watchdogBusy = true
                val previous = watchdog
                watchdog = target
                scope.launch {
                    val ok = ProviderManager.call(context, 5_000) {
                        it.setWatchdogConfig(target.enabled, target.autoRepairA11y, target.autoRepairShizuku)
                    } == true
                    if (!ok) {
                        watchdog = previous
                        snackbar?.show("看门狗配置保存失败：服务组件未连接", SnackbarType.ERROR)
                    } else {
                        watchdog = readWatchdog() ?: watchdog
                    }
                    watchdogBusy = false
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    text = when {
                        installedVersion == null -> "未安装"
                        needsInstall -> "有新版本（当前 v$installedVersion，随包 v${bundledVersion ?: "未知"}）"
                        else -> "已安装 v$installedVersion"
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (needsInstall) MaterialTheme.colorScheme.onSurface else Success,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "无障碍、内置 Shizuku、Termux 三条通道运行在独立服务组件中；" +
                        "其优点是主应用升级/重启不会导致无障碍掉线。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (installedVersion != null) {
                    // 把「已安装」与「真的能用」分开说：安装完但无障碍没开、或绑定失败时，
                    // 这里要直接给出原因，而不是让用户对着一个绿色标题猜
                    val ready = a11yEnabled && channelConnected && channelError == null
                    val status = when {
                        !a11yEnabled -> "通道未就绪：无障碍服务未开启"
                        channelError != null -> "通道未就绪：$channelError"
                        !channelConnected -> "通道未就绪：正在连接服务组件…"
                        else -> "通道已就绪"
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ready) Success else MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val result = ProviderManager.install(context)
                        // 安装器拉起后由用户确认；失败才需要页内提示，成功后靠 ON_RESUME 刷新
                        if (result is ProviderManager.InstallLaunch.Failed) {
                            snackbar?.show("安装服务组件失败：${result.reason}", SnackbarType.ERROR)
                        }
                    },
                    enabled = needsInstall,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            installedVersion == null -> "安装服务组件"
                            needsInstall -> "更新服务组件"
                            else -> "已是官方组件"
                        },
                    )
                }
                if (installedVersion != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { AgentAccessibilityService.openSettings(context) },
                        enabled = !a11yEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (a11yEnabled) "无障碍服务已开启" else "开启无障碍服务") }
                    if (!a11yEnabled) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "无障碍服务未开启：AI 暂时无法读取屏幕与点击操作，请在系统设置中打开「Happy Agent 自动操作」（属于服务组件应用）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // ---- 执行通道自检：五步实测，每步给出真实数值，不用对着「已安装」猜 ----
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            if (selfTestRunning) return@OutlinedButton
                            selfTestRunning = true
                            scope.launch {
                                selfTestSteps = ProviderManager.selfTest(context, a11yEnabled)
                                selfTestRunning = false
                            }
                        },
                        enabled = !selfTestRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (selfTestRunning) "正在自检…" else "运行执行通道自检") }
                    selfTestSteps?.let { steps ->
                        Spacer(Modifier.height(8.dp))
                        steps.forEach { step ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    text = if (step.ok) "✓" else "✕",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (step.ok) Success else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        step.title,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        step.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // ---- 看门狗 / 保活配置：写入 Provider 立即生效 ----
                    LaunchedEffect(installedVersion) {
                        watchdog = readWatchdog() ?: watchdog
                    }
                    if (watchdog != null) {
                        val w = watchdog!!
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "看门狗与保活",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                when {
                                    w.running -> "运行中 · 累计修复 ${w.repairCount} 次"
                                    w.enabled -> "已开启 · 服务未运行"
                                    else -> "未开启"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (w.running) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        WatchdogRow(
                            title = "保活看门狗",
                            description = "服务组件常驻后台，主应用被杀或重启期间无障碍不掉线；伴随一条低优先级常驻通知",
                            checked = w.enabled,
                            onChecked = { applyWatchdog(w.copy(enabled = it)) },
                        )
                        WatchdogRow(
                            title = "无障碍掉线自动修复",
                            description = if (w.canRepairA11y) {
                                "无障碍被系统关闭时自动重新启用（已获得安全设置写入授权，优先直写）"
                            } else {
                                "经内置 Shizuku 执行 settings 命令自动拉起（需 Shizuku 在线）；完成授权引导后可改用零依赖的直写方式"
                            },
                            checked = w.autoRepairA11y,
                            onChecked = { applyWatchdog(w.copy(autoRepairA11y = it)) },
                        )
                        WatchdogRow(
                            title = "Shizuku 服务自动恢复",
                            description = "检测到 Shizuku 离线时经无线 ADB 自动重拉（已配对可静默完成，未配对则需先完成首次引导）",
                            checked = w.autoRepairShizuku,
                            onChecked = { applyWatchdog(w.copy(autoRepairShizuku = it)) },
                        )
                        if (w.lastRepairMessage.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "最近动作：${w.lastRepairMessage}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(title = "系统权限", subtitle = "点击未授权项跳转对应授权页", tone = SettingsTone.Warm) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "授权完成后返回本页点「重新检测」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        // 去系统设置授完权再回来，本页不会自行重组，留一个手动刷新的入口
                        TextButton(onClick = { vm.refreshPermissions(context) }) { Text("重新检测") }
                    }
                    Spacer(Modifier.height(6.dp))
                    PermissionRadar(permissions, context, vm)
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            // 系统级入口：数字助理角色（长按电源键唤起）与通知感知（AI 的通知维度上下文）。
            // 两者都是系统授权，不进权限雷达（雷达枚举的是运行时权限），单独给一区引导。
            SettingsSection(title = "系统级入口", subtitle = "数字助理唤起与通知感知", tone = SettingsTone.Warm) {
                val activity = androidx.activity.compose.LocalActivity.current
                var assistantHeld by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(
                        com.phoneagent.feature.assistant.AssistantRole.isDefaultAssistant(context),
                    )
                }
                val roleLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                ) {
                    // 授权框关闭后回读状态（授权/拒绝都要刷新，否则按钮文案停在旧状态）
                    assistantHeld = com.phoneagent.feature.assistant.AssistantRole.isDefaultAssistant(context)
                }
                val notifEnabled = com.phoneagent.feature.notify.notificationListenerEnabled(context)
                Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                    Text(
                        "数字助理：设为默认助理后，长按电源键或边缘手势直接唤起 Happy Agent，" +
                            "唤起时的屏幕内容会带给任务（原来占这个位置的语音助手仍有各自的专属入口）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 区块内的主操作统一满宽按钮，与端侧视觉/数据页的行动按钮同一语言
                    androidx.compose.material3.Button(
                        enabled = !assistantHeld,
                        onClick = {
                            val host = activity ?: context as? android.app.Activity
                            // 拿得到系统授权框就弹框授权；拿不到（API<29 / 机型不支持该角色）时
                            // 兜底跳系统「数字助理应用」设置页手动指定——不再静默无反应
                            val request = if (android.os.Build.VERSION.SDK_INT >= 29) {
                                com.phoneagent.feature.assistant.AssistantRole.requestRole(host)
                            } else {
                                null
                            }
                            if (request != null) {
                                roleLauncher.launch(request)
                            } else {
                                com.phoneagent.feature.assistant.AssistantRole.openAssistantSettings(host ?: context)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (assistantHeld) "已是默认数字助理" else "设为系统数字助理")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "通知感知：开启后 AI 能读到通知（验证码/提醒），并支持「读取通知/清理通知」技能。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.OutlinedButton(
                        enabled = !notifEnabled,
                        onClick = {
                            // 裸 startActivity 在没有该设置页的 ROM 上会 ActivityNotFoundException 崩溃，
                            // 收口到带回退链与 NEW_TASK 标记的封装
                            com.phoneagent.feature.notify.openNotificationAccessSettings(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (notifEnabled) "通知使用权已开启" else "开启通知使用权") }
                }
                // 对外控制接口：广播契约在 TaskControlReceiver（下发任务/停止/查询状态）。
                // 开关统一走 SettingsToggleRow（原先是一颗裸 Switch，与全页开关语言不一致）
                SettingsToggleRow(
                    title = "对外控制接口",
                    subtitle = "允许 Tasker、MacroDroid 等自动化工具以广播驱动本应用（下发任务 / 停止 / 查询状态）",
                    value = st.externalControlEnabled,
                ) {
                    st.externalControlEnabled = it
                    save()
                }
                if (st.externalControlEnabled) {
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        androidx.compose.material3.OutlinedTextField(
                            value = st.externalControlToken,
                            onValueChange = { st.externalControlToken = it; save() },
                            label = { Text("广播口令（token）") },
                            placeholder = { Text("留空=仅靠开关（不推荐）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "广播示例：action=com.phoneagent.action.EXTERNAL_RUN_TASK，extras：text=任务内容、token=口令",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            SettingsSection(
                title = "执行通道优先级",
                subtitle = "shell 执行通路的优先顺序（内置 Shizuku 为主，Termux 可选）",
                tone = SettingsTone.Warm,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val channels = listOf(
                        "AUTO" to "自动",
                        "SHIZUKU" to "Shizuku",
                        "TERMUX" to "Termux",
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        channels.forEachIndexed { i, (key, label) ->
                            SegmentedButton(
                                selected = st.executionChannel == key,
                                onClick = { st.executionChannel = key; save() },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = channels.size),
                            ) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (st.executionChannel) {
                            "SHIZUKU" -> "仅认 Shizuku 服务就绪；未启动时可在下方一键启动。"
                            "TERMUX" -> "仅用 Termux（普通应用权限）：可跑 curl / python / 文本处理，不能执行系统命令。"
                            else -> "优先 Shizuku 服务，其次引导会话（server 拉起前后均可执行 shell），最后 Termux；都不可用则走无障碍执行。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

        // 执行通道：内置 Shizuku（一键启动 / 首次引导）+ Termux 探测。
        // 收进与分区同层的 20dp 栅格，卡片不再铺到屏幕边缘
        ShizukuChannelCard(vm)
        Spacer(Modifier.height(12.dp))
        TermuxChannelCard(vm)

        Spacer(Modifier.height(28.dp))
    }
}

// ==================== 看门狗配置面板的本地支撑 ====================

/** 看门狗状态快照（watchdogStatus JSON → 面板状态；字段契约见 IDeviceProvider.watchdogStatus 注释） */
private data class WatchdogUi(
    val enabled: Boolean,
    val autoRepairA11y: Boolean,
    val autoRepairShizuku: Boolean,
    val running: Boolean,
    val canRepairA11y: Boolean,
    val repairCount: Int,
    val lastRepairMessage: String,
)

/** 解析 Provider 回的看门狗状态 JSON；空串/格式错误返回 null（面板按未连接处理） */
private fun parseWatchdogStatus(json: String): WatchdogUi? = runCatching {
    val o = org.json.JSONObject(json)
    if (o.length() == 0) return null
    WatchdogUi(
        enabled = o.optBoolean("enabled", false),
        autoRepairA11y = o.optBoolean("autoRepairA11y", true),
        autoRepairShizuku = o.optBoolean("autoRepairShizuku", false),
        running = o.optBoolean("running", false),
        canRepairA11y = o.optBoolean("canRepairA11y", false),
        repairCount = o.optInt("repairCount", 0),
        lastRepairMessage = o.optString("lastRepairMessage", ""),
    )
}.getOrNull()

/** 看门狗配置开关行：左标题+说明，右 Switch（视觉与其他设置行一致，纯工具风格） */
@Composable
private fun WatchdogRow(
    title: String,
    description: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
