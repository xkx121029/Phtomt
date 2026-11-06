package com.phoneagent.ui.settings

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phoneagent.coding.env.CodingEnvClient
import com.phoneagent.ui.coding.openAllFilesAccessSettings
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.LocalSnackbar
import com.phoneagent.ui.components.SnackbarType
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.Success
import org.koin.compose.koinInject
import kotlinx.coroutines.launch

/**
 * 「编码 → 环境」页：设备内构建 APK 的完整工具链。
 *
 * 三段式结构与实际依赖顺序一致：先装伴生组件（能力载体）→ 再看工程识别结果
 * （决定装哪个 Gradle）→ 最后才是安装与构建。任务进度单独一段，安装与构建共用，
 * 因为组件一次只跑一个任务。
 */
@Composable
internal fun SettingsCodingEnv(onBack: () -> Unit) {
    val client = koinInject<CodingEnvClient>()
    val ui by client.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) { client.refresh() }
    // 从系统安装器/授权页返回时窗口回前台，必须重读安装与权限状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { client.refresh() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 启动类失败只可能来自本侧（绑定/路径/权限），统一弹一次，不留在页面里误导 */
    fun report(message: String?) {
        if (message != null) snackbar?.show(message, SnackbarType.ERROR)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsScreenPad)
            .padding(LocalNavClearance.current),
    ) {
        SettingsPageHeader("编码环境", onBack, subtitle = "PRoot Linux · JDK 17 · Gradle · Android SDK")
        Spacer(Modifier.height(AppSpacing.Page))

        EnvComponentSection(
            ui = ui,
            onInstall = { scope.launch { report(client.installComponent()) } },
            onGrantStorage = { report(client.openStorageSetup()) },
            onOpenAllFiles = { openAllFilesAccessSettings(context) },
        )
        Spacer(Modifier.height(AppSpacing.Lg))

        ToolchainSection(ui = ui, onRefresh = { scope.launch { client.refresh() } })
        Spacer(Modifier.height(AppSpacing.Lg))

        InstallSection(
            ui = ui,
            onInstall = { scope.launch { report(client.installEnvironment()) } },
        )
        Spacer(Modifier.height(AppSpacing.Lg))

        BuildSection(
            ui = ui,
            onBuild = { scope.launch { report(client.buildApk("assembleDebug")) } },
        )

        TaskSection(ui = ui, onCancel = { client.cancel() })
        Spacer(Modifier.height(AppSpacing.Xl))
    }
}

@Composable
private fun EnvComponentSection(
    ui: CodingEnvClient.UiState,
    onInstall: () -> Unit,
    onGrantStorage: () -> Unit,
    onOpenAllFiles: () -> Unit,
) {
    val buzz = rememberHapticClick()
    SettingsSection(
        title = "环境组件",
        subtitle = "targetSdk 28 的伴生应用：PRoot + Alpine Linux 的实际运行载体",
        tone = SettingsTone.Cool,
    ) {
        Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
            Text(
                installedText(ui),
                style = MaterialTheme.typography.bodyLarge,
                color = if (ui.needsInstall) MaterialTheme.colorScheme.onSurface else Success,
            )
            Spacer(Modifier.height(AppSpacing.Sm))
            Text(
                "Android 10 起系统禁止 targetSdk ≥ 29 的应用执行私有目录里的程序，" +
                    "编码环境因此由独立组件承载，主应用只经 AIDL 驱动它。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AppSpacing.Md))
            Button(
                onClick = { buzz(); onInstall() },
                enabled = ui.needsInstall,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        ui.installedVersion == null -> "安装环境组件"
                        ui.needsInstall -> "更新环境组件"
                        else -> "已是随包版本"
                    },
                )
            }
            if (ui.installedVersion != null) {
                Spacer(Modifier.height(AppSpacing.Sm))
                OutlinedButton(
                    onClick = { buzz(); onGrantStorage() },
                    enabled = !ui.storageGranted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (ui.storageGranted) "存储权限已授予" else "授予环境组件存储权限")
                }
            }
            if (!ui.sharedWritable) {
                Spacer(Modifier.height(AppSpacing.Xs))
                TextButton(onClick = { buzz(); onOpenAllFiles() }) {
                    Text("开启本应用的「所有文件访问」")
                }
                Text(
                    "私有工作区必须先镜像到共享存储，环境组件才读得到（跨 uid 无法直接访问应用私有目录）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ToolchainSection(ui: CodingEnvClient.UiState, onRefresh: () -> Unit) {
    val buzz = rememberHapticClick()
    val toolchain = ui.toolchain
    SettingsSection(
        title = "工程识别",
        subtitle = "按工程文件自动决定 Gradle 与 AGP 版本，不需要手填",
        tone = SettingsTone.Brand,
    ) {
        Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
            if (toolchain == null || !toolchain.ready) {
                Text(
                    "未识别到可构建的 Android 工程",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(AppSpacing.Xs))
                Text(
                    "请在编码页切换到含 gradle-wrapper.properties 的工作区。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("工程目录", style = MaterialTheme.typography.bodyMedium)
                Text(
                    toolchain.projectDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(AppSpacing.Sm))
                SettingsStatusRow("Gradle", "${toolchain.gradleVersion} · ${gradleSourceLabel(toolchain.gradleSource)}")
                SettingsDivider(inset = false)
                SettingsStatusRow("AGP", toolchain.agpVersion ?: "未识别")
                SettingsDivider(inset = false)
                SettingsStatusRow("compileSdk", toolchain.compileSdk.toString())
                SettingsDivider(inset = false)
                SettingsStatusRow("Build Tools", toolchain.buildTools)
            }
            Spacer(Modifier.height(AppSpacing.Md))
            OutlinedButton(onClick = { buzz(); onRefresh() }, modifier = Modifier.fillMaxWidth()) {
                Text("重新识别")
            }
        }
    }
}

@Composable
private fun InstallSection(ui: CodingEnvClient.UiState, onInstall: () -> Unit) {
    val buzz = rememberHapticClick()
    SettingsSection(
        title = "一键安装",
        subtitle = "Linux 根文件系统 · JDK · Gradle · Android SDK · aapt2 兼容层",
        tone = SettingsTone.Warm,
    ) {
        Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
            Text(
                "全部从官方源下载：Alpine、Adoptium JDK、services.gradle.org、" +
                    "cmdline-tools 与 Ubuntu base（给 x86_64 版 aapt2 当运行库）。下载走缓存，重复安装不会重复下载。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AppSpacing.Md))
            Button(
                onClick = { buzz(); onInstall() },
                enabled = ui.installedVersion != null && !ui.running && ui.toolchain?.ready == true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (ui.running) "任务执行中…" else "一键安装环境")
            }
            if (ui.envState.isNotEmpty()) {
                Spacer(Modifier.height(AppSpacing.Md))
                EnvStateRows(ui.envState)
            }
        }
    }
}

@Composable
private fun BuildSection(ui: CodingEnvClient.UiState, onBuild: () -> Unit) {
    val buzz = rememberHapticClick()
    SettingsSection(
        title = "构建 APK",
        subtitle = "在设备内执行 Gradle 的 assembleDebug",
        tone = SettingsTone.Cool,
    ) {
        Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
            Text(
                "需要先完成一键安装。aapt2 只有 x86_64 版，arm64 设备上由 qemu-x86_64 + glibc " +
                    "转译执行，首次构建会慢一些。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AppSpacing.Md))
            Button(
                onClick = { buzz(); onBuild() },
                enabled = ui.installedVersion != null && !ui.running && ui.toolchain?.ready == true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (ui.running) "任务执行中…" else "构建 Debug APK")
            }
            val path = ui.lastProjectPath
            if (path != null) {
                Spacer(Modifier.height(AppSpacing.Sm))
                Text(
                    "构建目录：$path",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "产物在工程内的 app/build/outputs/apk/debug/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 任务进度：安装与构建共用同一套回调，没有内容时整段不渲染 */
@Composable
private fun TaskSection(ui: CodingEnvClient.UiState, onCancel: () -> Unit) {
    if (ui.steps.isEmpty() && ui.logs.isEmpty() && ui.message == null) return
    SettingsSection(
        title = "任务进度",
        subtitle = "环境组件一次只跑一个任务，安装与构建共用这份进度",
        tone = SettingsTone.Neutral,
    ) {
        Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
            if (ui.steps.isNotEmpty()) StepList(ui.steps)
            if (ui.logs.isNotEmpty()) {
                Spacer(Modifier.height(AppSpacing.Sm))
                LogView(ui.logs)
            }
            val message = ui.message
            if (message != null) {
                Spacer(Modifier.height(AppSpacing.Md))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ui.finished && !ui.ok) MaterialTheme.colorScheme.error else Success,
                )
            }
            if (ui.running) {
                Spacer(Modifier.height(AppSpacing.Md))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("取消任务")
                }
            }
        }
    }
}