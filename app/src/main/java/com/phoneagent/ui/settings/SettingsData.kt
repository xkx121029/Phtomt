package com.phoneagent.ui.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.data.prefs.AppSettings
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.LocalBottomNavClearance
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.icons.AppIcons

/**
 * 数据与存储页：导出、清空与重置。
 *
 * 三组按「能不能撤销」排序——导出只读放最前，记忆/记录只清不恢复居中，
 * 整机重置放最后并单独占一张卡，避免误点。
 */
@Composable
internal fun SettingsData(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    // 清空动作的结果（成功是路径/提示，失败以 ERR: 开头）就地回显，不弹系统弹窗
    var message by remember { mutableStateOf<String?>(null) }

    val anomalies by vm.memoryAnomalies.collectAsState()
    val profile by vm.memoryProfile.collectAsState()
    val taskMemories by vm.memoryTaskMemories.collectAsState()
    val logs by vm.logs.collectAsState()

    LaunchedEffect(Unit) { vm.refreshMemory() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            .padding(bottom = LocalBottomNavClearance.current),
    ) {
        SettingsTopBar("数据与存储", onBack)
        Spacer(Modifier.height(16.dp))

        GroupCard {
            GroupHeader("导出", "保存到「下载/HappyPhoneAgent」，导出前自动脱敏手机号、身份证、银行卡")
            DataActionRow(
                icon = AppIcons.Description,
                iconTint = MaterialTheme.colorScheme.primary,
                title = "导出运行日志",
                subtitle = "当前 ${logs.size} 条日志汇总为一个文本文件",
                actionLabel = "导出",
                onAction = { message = vm.exportLogs(context, -1L, null) },
            )
            GroupDivider()
            DataActionRow(
                icon = AppIcons.FileDownload,
                iconTint = MaterialTheme.colorScheme.primary,
                title = "导出日志 JSON",
                subtitle = "按任务拆分，每个任务一个 JSON 文件",
                actionLabel = "导出",
                onAction = { message = vm.exportLogsJsonAll(context) },
            )
            GroupDivider()
            DataActionRow(
                icon = AppIcons.Report,
                iconTint = MaterialTheme.colorScheme.primary,
                title = "导出诊断报告",
                subtitle = "人话摘要 + 原始数据（含发往模型的完整上下文）",
                actionLabel = "导出",
                onAction = { message = vm.exportDiagnosticReport(context) },
            )
        }

        Spacer(Modifier.height(16.dp))

        GroupCard {
            GroupHeader("AI 记忆", "AI 在任务中记下的内容；清空后无法恢复")
            DataActionRow(
                icon = AppIcons.Report,
                iconTint = MaterialTheme.colorScheme.error,
                title = "清空异常记忆",
                subtitle = "失败与异常的经验记录（${anomalies.size} 条）",
                actionLabel = "清空",
                danger = true,
                onAction = {
                    vm.clearAnomalyMemory()
                    message = "已清空异常记忆"
                },
            )
            GroupDivider()
            DataActionRow(
                icon = AppIcons.Person,
                iconTint = MaterialTheme.colorScheme.error,
                title = "清空用户画像",
                subtitle = "AI 归纳的你的使用习惯与偏好（${profile.size} 条）",
                actionLabel = "清空",
                danger = true,
                onAction = {
                    vm.clearProfileMemory()
                    message = "已清空用户画像"
                },
            )
            GroupDivider()
            DataActionRow(
                icon = AppIcons.ListTodo,
                iconTint = MaterialTheme.colorScheme.error,
                title = "清空任务记忆",
                subtitle = "任务执行中的工作记忆（${taskMemories.size} 条）",
                actionLabel = "清空",
                danger = true,
                onAction = {
                    vm.clearTaskMemories()
                    message = "已清空任务记忆"
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        GroupCard {
            GroupHeader("调试记录与任务", "调试页展示的日志 / 对话 / 指标 / 执行轨迹，以及断点续传记录")
            DataActionRow(
                icon = AppIcons.DeleteOutline,
                iconTint = MaterialTheme.colorScheme.error,
                title = "清空调试记录",
                subtitle = "含任务会话归档，清空后调试页与侧边栏一并清空",
                actionLabel = "清空",
                danger = true,
                onAction = {
                    vm.clearDebug()
                    message = "已清空调试记录"
                },
            )
            GroupDivider()
            DataActionRow(
                icon = AppIcons.History,
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                title = "清除任务检查点",
                subtitle = "删除「长线任务」页的续传记录，不影响任务模板",
                actionLabel = "清除",
                danger = true,
                onAction = {
                    vm.clearCheckpoint(context)
                    message = "已清除任务检查点"
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        GroupCard {
            GroupHeader(
                "恢复默认设置",
                "清空 API 地址与密钥、模型库、执行与界面参数，回到首次安装的状态",
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                // 整机重置不可撤销：按钮点一次变红字确认，再点才执行
                var armed by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = {
                        if (armed) {
                            vm.saveSettings(AppSettings.Settings())
                            armed = false
                            message = "已恢复默认设置，请重新配置 AI 模型"
                        } else {
                            armed = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Text(
                        if (armed) "再点一次确认恢复（不可撤销）" else "恢复默认设置",
                        color = if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("ERR:")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * 数据行动作行：图标 + 标题 + 副标题 + 尾随动作按钮。
 * [danger] 的动作需要点两次——第一次把按钮文案换成确认语，第二次才真的执行。
 */
@Composable
private fun DataActionRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    actionLabel: String,
    danger: Boolean = false,
    onAction: () -> Unit,
) {
    val buzz = rememberHapticClick()
    var armed by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(AppRadii.Tile)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = {
                buzz()
                if (!danger || armed) {
                    armed = false
                    onAction()
                } else {
                    armed = true
                }
            },
        ) {
            Text(
                if (armed) "确认$actionLabel" else actionLabel,
                color = if (armed) MaterialTheme.colorScheme.error
                        else if (danger) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                fontWeight = if (armed) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
