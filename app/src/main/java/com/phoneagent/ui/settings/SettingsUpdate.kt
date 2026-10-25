package com.phoneagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.BuildConfig
import com.phoneagent.feature.update.UpdateChannel
import com.phoneagent.feature.update.UpdateState
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.components.LocalSnackbar
import com.phoneagent.ui.components.SnackbarType
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.appBorderStroke
import kotlinx.coroutines.launch

/**
 * 软件更新页：查看当前版本、选择接收频道、手动检查并安装新版本。
 *
 * 页面只有一条主线——「我现在是不是最新，不是的话一键装」。历史版本、更新日志详情都留给官网，
 * 这里不重复铺开：更新页要回答的是"要不要升"，不是"都改了什么"。
 *
 * 接收频道是本页唯一需要理解的概念，因此用一行胶囊把四个档位与其可见范围一次讲清，
 * 而不是塞进下拉菜单让用户猜。
 */
@Composable
internal fun SettingsUpdate(vm: MainViewModel, st: SettingsState, save: () -> Unit, onBack: () -> Unit) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val state by vm.updateState.collectAsState()

    // 进入页面即静默查一次：用户点进「软件更新」就是想确认有没有新版，
    // 不该还要再点一次"检查更新"。这条检查不弹任何提示，避免每次进页都打扰。
    LaunchedEffect(Unit) {
        vm.checkUpdate(st.updateBaseUrl, UpdateChannel.of(st.updateChannel))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsScreenPad)
            .padding(LocalNavClearance.current),
    ) {
        SettingsPageHeader("软件更新", onBack, subtitle = "当前版本与接收渠道")
        Spacer(Modifier.height(20.dp))

        // ---- 当前版本 + 检查 ----
        SettingsSection(title = "当前版本", tone = SettingsTone.Brand) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        AppIcons.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Happy Phone Agent",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val found = vm.checkUpdate(st.updateBaseUrl, UpdateChannel.of(st.updateChannel))
                            if (!found && vm.updateState.value is UpdateState.UpToDate) {
                                snackbar?.show("已是最新版本", SnackbarType.SUCCESS)
                            }
                        }
                    },
                    enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
                ) {
                    Text(if (state is UpdateState.Checking) "检查中…" else "检查更新")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- 状态：查询结果 / 下载进度 / 安装结果 ----
        UpdateStatusSection(vm = vm, state = state, snackbar = snackbar)

        Spacer(Modifier.height(16.dp))

        // ---- 接收频道 ----
        SettingsSection(
            title = "接收渠道",
            subtitle = "选定渠道后只会收到同档与更稳定档的版本",
            tone = SettingsTone.Cool,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                    UpdateChannel.entries.forEach { ch ->
                        // 渠道芯片统一走 SettingsChoiceChip（与提示词语言/主题选择同一套选中语言，
                        // 原先这里是 primaryContainer 实底，与其他页的描边选中态对不上）
                        SettingsChoiceChip(
                            label = ch.label,
                            selected = st.updateChannel == ch.id,
                            onClick = {
                                st.updateChannel = ch.id
                                save()
                                scope.launch { vm.checkUpdate(st.updateBaseUrl, ch) }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    UpdateChannel.of(st.updateChannel).summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsDivider()
            SettingsToggleRow(
                title = "启动时自动检查",
                subtitle = "打开 App 时静默查询新版本，有更新才提示",
                value = st.autoCheckUpdate,
                onChange = {
                    st.autoCheckUpdate = it
                    save()
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        // ---- 更新服务地址（可指向自建站点） ----
        SettingsSection(
            title = "更新服务",
            subtitle = "默认使用官方站点；自建部署时填自己的地址",
            tone = SettingsTone.Neutral,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                LabeledField("服务地址") {
                    androidx.compose.material3.OutlinedTextField(
                        value = st.updateBaseUrl,
                        onValueChange = { st.updateBaseUrl = it },
                        singleLine = true,
                        placeholder = { Text("https://hpa.linshuxu.us.kg") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { save(); snackbar?.show("已保存", SnackbarType.SUCCESS) }) {
                    Text("保存地址")
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** 更新状态区：随 [UpdateState] 变形，不同状态下只呈现"此刻要做什么" */
@Composable
private fun UpdateStatusSection(vm: MainViewModel, state: UpdateState, snackbar: com.phoneagent.ui.components.SnackbarState?) {
    when (state) {
        UpdateState.Idle, UpdateState.Checking -> Unit

        UpdateState.UpToDate -> {
            StatusCard(
                title = "已是最新版本",
                subtitle = "当前渠道下没有更新的版本",
                accent = MaterialTheme.colorScheme.primary,
                icon = AppIcons.CheckCircle,
            )
        }

        is UpdateState.Available -> {
            val release = state.release
            val download = state.download
            SettingsSection(title = "发现新版本", tone = SettingsTone.Warm) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        "${release.version}　${channelLabel(release.channel)}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    if (release.date.isNotBlank()) {
                        Text(
                            release.date,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (release.summary.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(release.summary, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (release.notes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        release.notes.take(8).forEach { note ->
                            Text(
                                "· $note",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            append("安装包 ${download?.sizeText?.ifBlank { null } ?: "大小未知"}")
                            if (download?.mirror == "lanzou") append(" · 蓝奏云镜像")
                            if (download?.password?.isNotBlank() == true) append(" · 提取码 ${download.password}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (download != null && download.url.isNotBlank()) {
                                vm.downloadAndInstallUpdate(release, download)
                            } else {
                                snackbar?.show("这一版暂时没有可下载的安装包", SnackbarType.WARNING)
                            }
                        },
                        enabled = download != null && download.url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.Icon(AppIcons.FileDownload, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("下载并安装")
                    }
                }
            }
        }

        is UpdateState.Downloading -> {
            SettingsSection(title = "正在下载", tone = SettingsTone.Warm) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    if (state.percent >= 0) {
                        Text("${state.percent}%", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("正在下载…", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已下载 ${humanSize(state.bytesRead)}" +
                            if (state.totalBytes > 0) " / ${humanSize(state.totalBytes)}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "可离开本页，下载会在后台完成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is UpdateState.Installing -> {
            StatusCard(
                title = if (state.silent) "正在静默安装" else "已交系统安装器",
                subtitle = if (state.silent) "通过 shell 通道安装，无需手动操作" else "请在系统弹窗中确认安装",
                accent = MaterialTheme.colorScheme.tertiary,
                icon = AppIcons.Launch,
            )
        }

        is UpdateState.Done -> {
            StatusCard(
                title = if (state.silent) "安装完成" else "已交给系统安装",
                subtitle = if (state.silent) "新版本已静默安装完成" else "请在安装器中完成最后一步",
                accent = MaterialTheme.colorScheme.primary,
                icon = AppIcons.CheckCircle,
            )
        }

        is UpdateState.Failed -> {
            SettingsSection(title = "更新失败", tone = SettingsTone.Warm) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    val download = state.download
                    val release = state.release
                    if (release != null && download != null) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { vm.downloadAndInstallUpdate(release, download) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("重试") }
                    }
                }
            }
        }
    }
}

/** 单条状态卡：图标 + 标题 + 说明，用于无需操作的结果态 */
@Composable
private fun StatusCard(
    title: String,
    subtitle: String,
    accent: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Surface(
        shape = RoundedCornerShape(AppRadii.Card),
        color = accent.copy(alpha = 0.08f),
        border = appBorderStroke(1.dp, accent.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 频道显示名：与服务端 CHANNEL_LABELS 保持一致，未知值原样回显 */
private fun channelLabel(id: String): String = UpdateChannel.of(id).label

/** 人类可读体积：与官网 formatSize 同口径，避免同一份 APK 在两处显示成不同大小 */
private fun humanSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}