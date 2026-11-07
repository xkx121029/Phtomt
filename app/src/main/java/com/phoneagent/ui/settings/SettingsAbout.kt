package com.phoneagent.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import com.phoneagent.ui.components.LocalNavClearance
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.theme.AppSpacing

/** 开源仓库地址：与推送远端一致（见项目 git remote） */
private const val REPO_GITHUB = "https://github.com/xkx121029/Phtomt"
private const val REPO_GITEE = "https://gitee.com/xkx1029/Phtomt"

/** Shizuku API 上游仓库：应用内开源许可声明指向这里 */
private const val SHIZUKU_REPO = "https://github.com/RikkaApps/Shizuku-API"
/** Shizuku 主程序上游仓库（server/starter 内嵌自这里） */
private const val SHIZUKU_MAIN_REPO = "https://github.com/RikkaApps/Shizuku"

/**
 * 渠道后缀：CI 用 versionCode 段位区分渠道（dev = 1000000+run，beta = 2000000+run），
 * 本地构建仍落在旧号段，后缀为空。不改 versionName 是因为服务端 compareVersion
 * 按 "." 分段做数值比较，带 "-dev" 后缀会让整段被解析成 0，破坏升级判断。
 */
private fun channelSuffix(): String = when {
    BuildConfig.VERSION_CODE >= 2_000_000 -> " · beta"
    BuildConfig.VERSION_CODE >= 1_000_000 -> " · dev"
    else -> ""
}

/**
 * 致谢条目：名称（点击跳仓库）+ 许可证 + 一句「它启发了我们什么」。
 *
 * 许可证一律按仓库实际的 LICENSE 写，不写「开源许可」笼统带过：
 * Eta 是 PolyForm Noncommercial 1.0.0（非商业许可，不是 OSI 开源许可），
 * dsh-Go 是 AGPL-3.0，pi 与 deepseek-harness 是 MIT。只有第四条的代码被内嵌才需要履行对应义务，
 * 本项目一条都没内嵌，仅取思路。
 */
private data class Credit(
    val name: String,
    val license: String,
    val url: String,
    /** 它启发了我们什么：写具体手法，不写「感谢」这类空话 */
    val inspiration: String,
)

private val CREDITS = listOf(
    Credit(
        name = "Eta",
        license = "PolyForm Noncommercial 1.0.0",
        url = "https://github.com/Mangi-11/Eta",
        inspiration = "浮层胶囊、完成卡片与系统工具调用链路的参考实现",
    ),
    Credit(
        name = "pi",
        license = "MIT License",
        url = "https://github.com/earendil-works/pi",
        inspiration = "系统级集成与可扩展性的参考",
    ),
    Credit(
        name = "deepseek-harness",
        license = "MIT License",
        url = "https://github.com/deepseek-ai/deepseek-harness",
        inspiration = "Coding 执行器与 agent 循环的参考",
    ),
    Credit(
        name = "dsh-Go",
        license = "AGPL-3.0",
        url = "https://github.com/ccch1mneyyy/dsh-Go",
        inspiration = "手机端 Coding 实现的参考",
    ),
    Credit(
        name = "Coda",
        license = "AGPL-3.0",
        url = "https://github.com/happy-everyday-everyweek/coda",
        inspiration = "安卓端 Agent 的工程组织与运行时方案，为同类问题上的取舍提供了参照",
    ),
    Credit(
        name = "MobileGym",
        license = "Apache License 2.0",
        url = "https://github.com/Purewhiter/mobilegym",
        inspiration = "面向移动 GUI Agent 的模拟研究平台，任务设计与可验证思路的参照",
    ),
    Credit(
        name = "Aether 扶摇",
        license = "GPL-3.0",
        url = "https://github.com/Zhou-Shilin/Aether",
        inspiration = "「进行中」状态的回显手法：过程弱化、结果成卡、思考中微光扫过文字",
    ),
)

/**
 * 关于与帮助页：版本、开源仓库、使用说明与隐私边界。
 *
 * 使用说明只写「不看就会踩坑」的几条（先给权限、配模型、AI 不做不可逆操作），
 * 不复述 README——复述一遍等于给用户多一层要读的文档。
 *
 * 「调试与测试」入口也收在这里：它们是开发工具，不该和 Agent / 设置这类日常入口
 * 并排出现在首屏；需要的人知道到「关于」里找。
 */
@Composable
internal fun SettingsAbout(
    onBack: () -> Unit,
    /** 打开调试页（日志 / 指标 / 轨迹） */
    onOpenDebug: () -> Unit = {},
    /** 打开模型回归测试页 */
    onOpenTest: () -> Unit = {},
    /** 打开「开源与致谢」二级页：许可与致谢合在一处，主页只留一行入口 */
    onOpenLicenses: () -> Unit = {},
    /** 打开更新页：版本号在线，检查更新不在同一页 */
    onOpenUpdate: () -> Unit = {},
    /** 打开快速开始：使用说明只留结论，步骤交给快速开始那一页 */
    onOpenQuickStart: () -> Unit = {},
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsScreenPad)
            // 悬浮导航栏浮在内容之上：滚动视口铺到屏幕底，只给末项让出净空
            .padding(LocalNavClearance.current),
    ) {
        SettingsPageHeader("关于与帮助", onBack, subtitle = "版本信息、开源仓库与隐私边界")
        Spacer(Modifier.height(AppSpacing.Page))

        // 应用标识：不放进带色相的分区，独立成一张中性卡，作为页面的题头
        SettingsPlainCard {
            Column(Modifier.padding(AppSpacing.Page)) {
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
                Spacer(Modifier.height(AppSpacing.Md))
                Text(
                    "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）" + channelSuffix(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "MIT License · Copyright (c) 2026 Happy Phone Agent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(AppSpacing.Sm))
                // 版本号与更新动作原先分在两页：看到版本却不知道去哪查新版本，这里直接给入口
                ActionRow(label = "检查更新", onClick = onOpenUpdate)
            }
        }

        Spacer(Modifier.height(AppSpacing.Lg))

        SettingsSection(
            title = "开源仓库",
            subtitle = "代码、版本历史与更新日志都在这里",
            tone = SettingsTone.Neutral,
        ) {
            LinkRow("GitHub", REPO_GITHUB) { openLink(context, REPO_GITHUB) }
            SettingsDivider(inset = false)
            LinkRow("Gitee", REPO_GITEE) { openLink(context, REPO_GITEE) }
        }

        Spacer(Modifier.height(AppSpacing.Lg))

        // 许可与致谢合成一页：两张静态长表放在主页只会让「关于」变成文档堆，
        // 主页留一行入口，想看的人点进去看
        SettingsSection(
            title = "开源与致谢",
            subtitle = "第三方许可，以及参考过的实现",
            tone = SettingsTone.Neutral,
        ) {
            SettingsRow(
                icon = AppIcons.Code,
                title = "许可与致谢清单",
                subtitle = "Shizuku 许可 · 7 个参考项目",
                tone = SettingsTone.Neutral,
                onClick = onOpenLicenses,
            )
        }

        Spacer(Modifier.height(AppSpacing.Lg))

        SettingsSection(title = "使用说明", tone = SettingsTone.Neutral) {
            Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
                // 五条压成三条：操作步骤归「快速开始」，这里只留不看就会踩坑的结论
                Bullet("先在「快速开始」里配好模型、开好无障碍，再回 Agent 页直接说需求")
                Bullet("支付、删除、发布这类不可逆操作 AI 不会执行，会在动手前停下来交给你")
                Bullet("任务执行期间，悬浮窗可随时停止任务、补充说明或接管操作")
                SettingsDivider(inset = false)
                ActionRow(label = "打开快速开始", onClick = onOpenQuickStart)
            }
        }

        Spacer(Modifier.height(AppSpacing.Lg))

        SettingsSection(title = "隐私说明", tone = SettingsTone.Neutral) {
            Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
                Bullet("日志、记忆与任务记录只存在本机，不会上传到本项目自己的服务器")
                Bullet("执行任务时，当前页面的控件文字与截图会发送给你所配置的模型服务，用于决策")
                Bullet("支付、密码、验证码等敏感页面为只读，AI 不会读取或输入其中内容")
                Bullet("导出的日志与诊断报告会先脱敏手机号、身份证、银行卡再落盘")
                Bullet("清除 AI 记忆、调试记录或恢复默认设置后不可撤销，见「数据与存储」")
            }
        }

        Spacer(Modifier.height(AppSpacing.Lg))

        // 开发者工具：调试页与模型回归测试属于排查手段，不是日常功能，
        // 放在「关于」里而不是首页快捷入口——首页那几个位置应该留给用户真正会用的东西
        SettingsSection(
            title = "开发者工具",
            subtitle = "排查问题、验证模型能力时使用",
            tone = SettingsTone.Neutral,
        ) {
            SettingsRow(
                icon = AppIcons.Code,
                title = "调试",
                subtitle = "日志 / 指标 / 执行轨迹",
                tone = SettingsTone.Neutral,
                onClick = onOpenDebug,
            )
            SettingsDivider()
            SettingsRow(
                icon = AppIcons.Science,
                title = "测试",
                subtitle = "模型回归校验",
                tone = SettingsTone.Neutral,
                onClick = onOpenTest,
            )
        }

        Spacer(Modifier.height(AppSpacing.Xl))
    }
}

/**
 * 「开源与致谢」二级页：第三方许可 + 参考过的实现。
 *
 * 从关于页进入；返回按两层口径直接回设置首页（与其余子页一致，不引入第三层）。
 */
@Composable
internal fun SettingsLicenses(onBack: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsScreenPad)
            .padding(LocalNavClearance.current),
    ) {
        SettingsPageHeader("开源与致谢", onBack, subtitle = "第三方许可与参考实现")
        Spacer(Modifier.height(AppSpacing.Page))

        SettingsSection(
            title = "开源许可",
            subtitle = "本应用使用的第三方开源代码",
            tone = SettingsTone.Neutral,
        ) {
            LinkRow("Shizuku API（MIT License）", SHIZUKU_REPO) { openLink(context, SHIZUKU_REPO) }
            SettingsDivider(inset = false)
            LinkRow("Shizuku（Apache License 2.0）", SHIZUKU_MAIN_REPO) { openLink(context, SHIZUKU_MAIN_REPO) }
            SettingsDivider(inset = false)
            Column(Modifier.padding(horizontal = AppSpacing.Lg, vertical = AppSpacing.Sm)) {
                Bullet("shell 执行通道基于 Shizuku API 构建，源码随本应用开源仓库一并分发（third_party/shizuku）")
                Bullet("Shizuku API 版权所有 © 2021 RikkaW，依 MIT 许可证使用；完整许可证文本见源码 LICENSE 文件")
                Bullet("Shizuku 主程序（server/starter）亦随「服务组件」一并分发，免装官方 App 直接拉起执行服务；依 Apache 2.0 许可证使用并保留其版权声明")
            }
        }

        Spacer(Modifier.height(AppSpacing.Lg))

        SettingsSection(
            title = "致谢",
            subtitle = "以下项目只作思路参考，代码未内嵌进本应用",
            tone = SettingsTone.Neutral,
        ) {
            CREDITS.forEachIndexed { index, credit ->
                if (index > 0) SettingsDivider(inset = false)
                CreditRow(credit) { openLink(context, credit.url) }
            }
        }

        Spacer(Modifier.height(AppSpacing.Xl))
    }
}

/** 行内动作：左标题右箭头，用于卡片/分区里的轻量跳转（检查更新、打开快速开始） */
@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    val buzz = rememberHapticClick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                buzz()
                onClick()
            }
            .padding(vertical = AppSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(AppSpacing.Xxs))
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 致谢行：第一行是项目名 + 许可证，第二行写它具体启发了什么；整行可点，点击即跳仓库。
 *
 * 不复用 [LinkRow]：那里第二行展示的是裸 URL，而这里 URL 不是给人读的，
 * 换成「启发了什么」才有信息量。视觉仍是同一套列表行（左文右按钮、同间距、同分隔线）。
 */
@Composable
private fun CreditRow(credit: Credit, onClick: () -> Unit) {
    val buzz = rememberHapticClick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { buzz(); onClick() }
            .padding(start = AppSpacing.Lg, end = AppSpacing.Sm, top = AppSpacing.Md, bottom = AppSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(credit.name, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(AppSpacing.Sm))
                Text(
                    credit.license,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                credit.inspiration,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { buzz(); onClick() }) { Text("打开") }
    }
}

/** 链接行：标题 + 右侧「打开」，点击交系统浏览器 */
@Composable
private fun LinkRow(label: String, url: String, onClick: () -> Unit) {
    val buzz = rememberHapticClick()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = AppSpacing.Lg, end = AppSpacing.Sm, top = AppSpacing.Md, bottom = AppSpacing.Md),
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
    Row(Modifier.fillMaxWidth().padding(vertical = AppSpacing.Sm)) {
        Text(
            "·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = AppSpacing.Sm),
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