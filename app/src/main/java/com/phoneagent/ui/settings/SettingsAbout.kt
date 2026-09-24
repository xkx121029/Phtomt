package com.phoneagent.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.BuildConfig
import com.phoneagent.ui.components.LocalBottomNavClearance
import com.phoneagent.ui.components.rememberHapticClick

/** 开源仓库地址：与推送远端一致（见项目 git remote） */
private const val REPO_GITHUB = "https://github.com/xkx121029/Phtomt"
private const val REPO_GITEE = "https://gitee.com/xkx1029/Phtomt"

/**
 * 关于与帮助页：版本、开源仓库、使用说明与隐私边界。
 *
 * 使用说明只写「不看就会踩坑」的几条（先给权限、配模型、AI 不做不可逆操作），
 * 不复述 README——复述一遍等于给用户多一层要读的文档。
 */
@Composable
internal fun SettingsAbout(onBack: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            .padding(bottom = LocalBottomNavClearance.current),
    ) {
        SettingsTopBar("关于与帮助", onBack)
        Spacer(Modifier.height(16.dp))

        GroupCard {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Happy Phone Agent",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "用自然语言指挥手机自动完成跨应用任务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "MIT License · Copyright (c) 2026 Happy Phone Agent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        GroupCard {
            GroupHeader("开源仓库", "代码、版本历史与更新日志都在这里")
            LinkRow("GitHub", REPO_GITHUB) { openLink(context, REPO_GITHUB) }
            GroupDivider()
            LinkRow("Gitee", REPO_GITEE) { openLink(context, REPO_GITEE) }
        }

        Spacer(Modifier.height(16.dp))

        GroupCard {
            GroupHeader("使用说明")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Bullet("先在「AI 模型配置」里填好 API 地址与密钥并保存，配置未通过连接测试不会入库")
                Bullet("再在「权限与执行通道」里开启无障碍服务；要用 shell 能力还需配对无线 ADB 或启用 Shizuku")
                Bullet("在 Agent 页直接说需求即可；执行中会先给计划，你确认后才动手")
                Bullet("支付、删除、发布这类不可逆操作 AI 不会执行，会在动手前停下来交给你")
                Bullet("任务执行期间的悬浮窗可随时停止任务、补充说明或接管操作")
            }
        }

        Spacer(Modifier.height(16.dp))

        GroupCard {
            GroupHeader("隐私说明")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Bullet("日志、记忆与任务记录只存在本机，不会上传到本项目自己的服务器")
                Bullet("执行任务时，当前页面的控件文字与截图会发送给你所配置的模型服务，用于决策")
                Bullet("支付、密码、验证码等敏感页面为只读，AI 不会读取或输入其中内容")
                Bullet("导出的日志与诊断报告会先脱敏手机号、身份证、银行卡再落盘")
                Bullet("清除 AI 记忆、调试记录或恢复默认设置后不可撤销，见「数据与存储」")
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** 链接行：标题 + 右侧「打开」，点击交系统浏览器 */
@Composable
private fun LinkRow(label: String, url: String, onClick: () -> Unit) {
    val buzz = rememberHapticClick()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                url.removePrefix("https://"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { buzz(); onClick() }) { Text("打开") }
    }
}

/** 说明条目：圆点 + 正文 */
@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 交系统浏览器打开外链；设备上没有可处理的应用时静默忽略 */
private fun openLink(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
