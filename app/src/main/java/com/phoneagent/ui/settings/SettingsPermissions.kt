package com.phoneagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import android.os.Build
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phoneagent.device.a11y.AgentAccessibilityService
import com.phoneagent.engine.runtime.AssistantPrimaryAction
import com.phoneagent.engine.runtime.AssistantRedirect
import com.phoneagent.engine.runtime.AssistantRoleFacts
import com.phoneagent.engine.runtime.AssistantRoleMessages
import com.phoneagent.engine.runtime.AssistantRolePlanner
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
            // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
            @Suppress("MaxLineLength")
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
                        // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
                        @Suppress("MaxLineLength")
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
                        // vivo/OriginOS + Android 13+ 专属：侧载应用的无障碍开关会被系统
                        // 自动撤销（受限设置），这是"开了又自动关"的首要根因，必须先解除
                        if (Build.MANUFACTURER.contains("vivo", ignoreCase = true) ||
                            Build.MANUFACTURER.contains("iqoo", ignoreCase = true) ||
                            Build.VERSION.SDK_INT >= 33
                        ) {
                            Spacer(Modifier.height(8.dp))
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
                                    .padding(10.dp),
                            ) {
                                Text(
                                    "若开关开了又被自动弹回（vivo/OriginOS 及 Android 13+ 常见）：\n" +
                                        "1. 系统设置 → 应用 → 服务组件 → 右上角菜单 → 允许受限设置\n" +
                                        "2. i管家 → 应用管理 → 服务组件：允许自启动、后台高耗电\n" +
                                        "3. 多任务卡片下拉锁定本应用与服务组件\n" +
                                        "完成后再回来开启无障碍即可，之后掉线会自动恢复。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
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
                        // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
                        @Suppress("MaxLineLength")
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
                        // 自动修复整条链路失效的明确告警：直写无授权 + Shizuku 不在线时，
                        // 看门狗对无障碍掉线无能为力（静默 no-op）——必须让用户看见这一点，
                        // 否则表现为"开了自动修复但开关还是被关"且毫无线索
                        if (!w.canRepairA11y && w.shizukuState != 2) {
                            Spacer(Modifier.height(8.dp))
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                                    .padding(10.dp),
                            ) {
                                Text(
                                    "自动修复当前不可用：既无安全设置写入授权，Shizuku 也未连接。无障碍被系统关闭后将无法自动恢复。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(4.dp))
                                // 最省事的修复路径：电脑连一次手机执行一条 adb 授权，
                                // 此后直写链路永久生效（无需 Shizuku 常驻）
                                Text(
                                    "连接电脑后执行：adb shell pm grant com.phoneagent.provider android.permission.WRITE_SECURE_SETTINGS",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                        if (w.lastRepairMessage.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "最近动作：${w.lastRepairMessage}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 崩溃现场：无障碍"开了又自己关"多数是服务进程异常被系统停用，
                        // 这里透出 Provider 全局兜底记录的最近一次异常，便于定位根因
                        if (w.lastCrashMessage.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "最近一次异常（第 ${w.crashCount} 次，已被兜底拦截）：${w.lastCrashMessage.lineSequence().firstOrNull()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        // ---- 故障态识别：开关在列但服务不运行（小米「此服务出现故障无法运行」）----
                        // 此时看门狗会自动重置最多 3 次；仍失败说明是被 ROM 冻结/省电策略杀掉，
                        // 只能靠系统级保活设置解决，必须明确告知用户下一步动作
                        if (w.a11yEnabledInList && !w.a11yConnected) {
                            Spacer(Modifier.height(8.dp))
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                                    .padding(10.dp),
                            ) {
                                Text(
                                    if (w.a11yMasterEnabled) {
                                        "无障碍开关处于开启状态，但服务未在运行（系统显示「此服务出现故障无法运行」）。" +
                                            "常见原因是系统冻结/清理了服务组件的后台进程。看门狗会自动重置；" +
                                            "若反复失败，请按下方系统保活设置处理。"
                                    } else {
                                        "无障碍总开关已被系统关闭（服务组件仍在启用列表中）。看门狗会尝试自动补开。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                // 无安全设置写入授权、又没有 Shizuku 时，看门狗无法自行重置故障态，
                                // 唯一可靠的恢复手段就是用户手动关再开一次——给出一键跳转，别让用户自己找
                                Spacer(Modifier.height(8.dp))
                                androidx.compose.material3.OutlinedButton(
                                    onClick = {
                                        runCatching { AgentAccessibilityService.openSettings(context) }
                                            .onFailure { snackbar?.show("无法打开无障碍设置", SnackbarType.ERROR) }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("打开无障碍设置（关闭再开启一次）") }
                                // 小米/红米/HyperOS：进程被安全中心冻结是「服务故障」的首因，
                                // 自启动开关只有系统界面能改（无公开 API），直接跳到对应设置页
                                // 此处是面向模型/用户的中文长文案或命令模板，硬折行会切断语义，故按规则豁免行长限制
                                @Suppress("MaxLineLength")
                                val miui = Build.MANUFACTURER.contains("xiaomi", ignoreCase = true) ||
                                    Build.MANUFACTURER.contains("redmi", ignoreCase = true) ||
                                    Build.BRAND.contains("redmi", ignoreCase = true) ||
                                    Build.BRAND.contains("poco", ignoreCase = true)
                                if (miui) {
                                    Spacer(Modifier.height(6.dp))
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = {
                                            val jumped = runCatching {
                                                context.startActivity(
                                                    android.content.Intent().apply {
                                                        component = android.content.ComponentName(
                                                            "com.miui.securitycenter",
                                                            "com.miui.permcenter.autostart.AutoStartManagementActivity",
                                                        )
                                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    },
                                                )
                                            }.isSuccess
                                            if (!jumped) {
                                                snackbar?.show(
                                                    "未找到小米自启动管理页，请在安全中心/手机管家里手动允许服务组件自启动",
                                                    SnackbarType.ERROR,
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("打开自启动管理（允许服务组件自启动）") }
                                }
                            }
                        }

                        // ---- 系统保活设置：ROM 的进程冻结是无障碍"故障"的首因，应用无法自行设置 ----
                        Spacer(Modifier.height(12.dp))
                        Text("系统保活设置", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "在服务组件（无界面应用）的应用信息里把「省电策略」设为「无限制」，并在 i管家/安全中心里允许自启动。这些设置只能由系统界面完成。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                                        )
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }.onFailure { snackbar?.show("无法打开电池优化设置", SnackbarType.ERROR) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("打开电池优化设置") }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            android.net.Uri.parse("package:${ProviderManager.PACKAGE}"),
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }.onFailure { snackbar?.show("无法打开服务组件应用信息", SnackbarType.ERROR) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("打开服务组件应用信息（省电策略/自启动）") }
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
                // 事实来自系统，判断与文案来自引擎契约（engine/runtime/AssistantRolePlan.kt）：
                // UI 只负责读 facts、按 primaryAction 执行、把 redirects 按顺序 resolve。
                // 不再在本页写第二套"为什么不行"的说法——两份措辞一定会在两个页面里分叉。
                var roleFacts by remember { mutableStateOf(readAssistantRoleFacts(context)) }
                var roleHint by remember { mutableStateOf<String?>(null) }
                val rolePlan = remember(roleFacts) { AssistantRolePlanner.plan(roleFacts) }
                val roleLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                ) {
                    // 授权框返回后按契约重读 facts 再 plan：仍未持有则给 KEY_DENIED 的中文成品文案
                    roleFacts = readAssistantRoleFacts(context)
                    roleHint = if (roleFacts.roleHeld) {
                        null
                    } else {
                        AssistantRoleMessages.textOf(AssistantRoleMessages.KEY_DENIED)
                    }
                }
                val notifEnabled = com.phoneagent.feature.notify.notificationListenerEnabled(context)
                Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                    Text(
                        rolePlan.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 区块内的主操作统一满宽按钮，与端侧视觉/数据页的行动按钮同一语言
                    Button(
                        enabled = rolePlan.primaryAction != AssistantPrimaryAction.NONE,
                        onClick = {
                            when (rolePlan.primaryAction) {
                                AssistantPrimaryAction.REQUEST_ROLE -> {
                                    val request = com.phoneagent.feature.assistant.AssistantRole.requestRole(activity)
                                    if (request != null) {
                                        roleHint = null
                                        roleLauncher.launch(request)
                                    } else {
                                        // 拿不到授权框：按契约顺序跳设置页，全部失败才报"找不到入口"
                                        val opened = openFirstAssistantRedirect(context, rolePlan.redirects)
                                        roleHint = if (opened) {
                                            rolePlan.failureText
                                                ?: rolePlan.requestBlockedReason
                                                ?: AssistantRoleMessages.textOf(
                                                    AssistantRoleMessages.KEY_NO_SETTINGS_PAGE,
                                                )
                                        } else {
                                            AssistantRoleMessages.textOf(AssistantRoleMessages.KEY_NO_SETTINGS_PAGE)
                                        }
                                    }
                                }

                                AssistantPrimaryAction.OPEN_SETTINGS -> {
                                    val opened = openFirstAssistantRedirect(context, rolePlan.redirects)
                                    roleHint = if (opened) {
                                        rolePlan.failureText ?: rolePlan.explanation
                                    } else {
                                        AssistantRoleMessages.textOf(AssistantRoleMessages.KEY_NO_SETTINGS_PAGE)
                                    }
                                }

                                AssistantPrimaryAction.NONE -> Unit
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(rolePlan.primaryLabel)
                    }

                    // 说明区：不能弹授权框的原因 / 失败原因，全部是契约里的中文成品
                    val explain = roleHint ?: rolePlan.requestBlockedReason
                    if (explain != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            explain,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // 候选链：按 ROM 兼容度排序，逐个可点；文案同样来自契约
                    if (rolePlan.primaryAction == AssistantPrimaryAction.OPEN_SETTINGS || roleHint != null) {
                        Spacer(Modifier.height(2.dp))
                        rolePlan.redirects.forEach { redirect ->
                            TextButton(
                                onClick = {
                                    val ok = openAssistantRedirect(context, redirect.action, redirect.requiresResolve)
                                    roleHint = if (ok) {
                                        roleHint
                                    } else {
                                        AssistantRoleMessages.textOf(AssistantRoleMessages.KEY_NO_SETTINGS_PAGE)
                                    }
                                },
                            ) {
                                Text(redirect.label)
                            }
                        }
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
                // 开关统一走 SettingsToggleRow（原先是一颗裸 Switch，与全页开关语言不一致）。
                // 口令不足 8 位时开关不可开：空口令的 exported 接收器等于没有闸门
                val tokenTooShort = st.externalControlToken.trim().length <
                    com.phoneagent.feature.entry.TaskControlReceiver.MIN_TOKEN_LENGTH
                SettingsToggleRow(
                    title = "对外控制接口",
                    subtitle = if (tokenTooShort && !st.externalControlEnabled) {
                        "允许 Tasker、MacroDroid 等自动化工具以广播驱动本应用（先设置至少 8 位口令才能开启）"
                    } else {
                        "允许 Tasker、MacroDroid 等自动化工具以广播驱动本应用（下发任务 / 停止 / 查询状态）"
                    },
                    value = st.externalControlEnabled,
                ) { want ->
                    // 想开但口令不够：只把开关打回关闭，并把焦点引到口令框（下方红字说明原因），
                    // 不做静默忽略——否则用户会以为开关坏了
                    if (want && tokenTooShort) {
                        st.externalControlEnabled = false
                    } else {
                        st.externalControlEnabled = want
                    }
                    save()
                }
                if (st.externalControlEnabled) {
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        androidx.compose.material3.OutlinedTextField(
                            value = st.externalControlToken,
                            onValueChange = { st.externalControlToken = it; save() },
                            label = { Text("广播口令（token）") },
                            placeholder = { Text("至少 8 位，用于校验广播来源") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        if (tokenTooShort) {
                            Text(
                                "口令少于 8 位时，任何应用都能伪造广播下发任务；请补足后再使用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
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
    val shizukuState: Int = 0,
    val crashCount: Int = 0,
    val lastCrashMessage: String = "",
    val a11yConnected: Boolean = false,
    val a11yEnabledInList: Boolean = false,
    val a11yMasterEnabled: Boolean = false,
)

/** 解析 Provider 回的看门狗状态 JSON；空串/格式错误返回 null（面板按未连接处理） */
private fun parseWatchdogStatus(json: String): WatchdogUi? = runCatching {
    val o = org.json.JSONObject(json)
    if (o.length() == 0) return null
    WatchdogUi(
        enabled = o.optBoolean("enabled", true),
        autoRepairA11y = o.optBoolean("autoRepairA11y", true),
        autoRepairShizuku = o.optBoolean("autoRepairShizuku", false),
        running = o.optBoolean("running", false),
        canRepairA11y = o.optBoolean("canRepairA11y", false),
        repairCount = o.optInt("repairCount", 0),
        lastRepairMessage = o.optString("lastRepairMessage", ""),
        shizukuState = o.optInt("shizukuState", 0),
        crashCount = o.optInt("crashCount", 0),
        lastCrashMessage = o.optString("lastCrashMessage", ""),
        a11yConnected = o.optBoolean("a11yConnected", false),
        a11yEnabledInList = o.optBoolean("a11yEnabledInList", false),
        a11yMasterEnabled = o.optBoolean("a11yMasterEnabled", false),
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

// ---------- 数字助理角色：事实读取与跳转执行（契约见 docs/assistant-role-contract.md） ----------

/**
 * 读出契约要求的 5 个事实，交给 [AssistantRolePlanner] 判断。
 * 低版本只报"不支持"，不去碰 API 29 的 RoleManager 类。
 */
private fun readAssistantRoleFacts(context: android.content.Context): AssistantRoleFacts {
    val api = Build.VERSION.SDK_INT
    val pkg = context.packageName
    if (api < AssistantRolePlanner.ROLE_API) {
        return AssistantRoleFacts(api, false, false, null, pkg)
    }
    return readAssistantRoleFactsApi29(context, api, pkg)
}

/** API 29+ 的读取实现：RoleManager 只在 29 之后存在，单列一个带注解的函数避免低版本触类 */
@androidx.annotation.RequiresApi(AssistantRolePlanner.ROLE_API)
// 此处刻意兜底捕获系统服务/协议异常，避免单个失败打断整体流程，故按规则豁免
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun readAssistantRoleFactsApi29(
    context: android.content.Context,
    api: Int,
    pkg: String,
): AssistantRoleFacts = try {
    val rm = context.getSystemService(android.app.role.RoleManager::class.java)
    AssistantRoleFacts(
        apiLevel = api,
        roleAvailable = rm?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true,
        roleHeld = rm?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true,
        // 持有者包名无法用公开 SDK 稳定获取（getRoleHolders 不在第三方可见 API 内），
        // 契约允许传 null：只影响「被占用」的说明文案，主流程仍会尝试弹授权框
        roleHolderPackage = null,
        selfPackage = pkg,
    )
} catch (t: Throwable) {
    AssistantRoleFacts(api, false, false, null, pkg)
}

/** 按契约顺序尝试候选链：第一条能 resolve 的就打开，返回是否成功 */
private fun openFirstAssistantRedirect(context: android.content.Context, redirects: List<AssistantRedirect>): Boolean =
    redirects.any { openAssistantRedirect(context, it.action, it.requiresResolve) }

/** 单条候选：requiresResolve 为 true 时先探测，避免在删掉该页的 ROM 上抛 ActivityNotFoundException */
// 此处刻意兜底捕获系统服务/协议异常，避免单个失败打断整体流程，故按规则豁免
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun openAssistantRedirect(
    context: android.content.Context,
    action: String,
    requiresResolve: Boolean,
): Boolean = try {
    val intent = android.content.Intent(action)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    if (requiresResolve && intent.resolveActivity(context.packageManager) == null) {
        false
    } else {
        context.startActivity(intent)
        true
    }
} catch (t: Throwable) {
    false
}