package com.phoneagent.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.phoneagent.ui.MainViewModel
import com.phoneagent.ui.components.rememberHapticClick
import com.phoneagent.ui.icons.AppIcons
import com.phoneagent.ui.settings.FirstTasks
import com.phoneagent.ui.settings.SettingsState
import com.phoneagent.ui.settings.commitModelConfig
import com.phoneagent.ui.settings.modelReady
import com.phoneagent.ui.theme.AppRadii
import com.phoneagent.ui.theme.AppSize
import com.phoneagent.ui.theme.AppSpacing
import com.phoneagent.ui.theme.AppTheme
import com.phoneagent.ui.theme.appBorderStroke

/**
 * 引导第 6 步：试第一个任务。
 *
 * 这一步是向导的收尾，也是它的验收——前面配的模型与权限在这里第一次真正被使用。
 * 例子与「快速开始」共用 [FirstTasks]，两处不会各写一套文案。
 *
 * 没配 Key 时不给「试试」的假入口：直接说明先回上一步，或者进主界面后自己输入。
 */
@Composable
internal fun OnboardingFirstTaskPage(
    vm: MainViewModel,
    st: SettingsState,
    onFinish: () -> Unit,
    onStartTask: () -> Unit,
    onBackToModel: () -> Unit,
) {
    val modelReady = st.modelReady()

    /**
     * 起跑：先把配置落盘、落盘完成后再发任务。
     *
     * 顺序不能反——引擎按已保存的设置读 key/model，先起跑再保存会让第一步决策用旧配置。
     * 落盘与起跑都交给 [MainViewModel.saveThenStartTask]：它跑在 ViewModel 作用域里，
     * 不受本页组合销毁影响（点完这一下页面就退场了）。
     */
    fun start(task: String) {
        commitModelConfig(st)
        vm.saveThenStartTask(st.toSettings(), task)
        onStartTask()
    }

    OnboardingPage(
        content = {
            OnboardingPageHeader(
                icon = AppIcons.Launch,
                title = if (modelReady) "试试交代第一件事" else "最后一步：开始使用",
                body = if (modelReady) {
                    "挑一个例子直接跑，任务进度会实时播报；也可以跳过，回 Agent 页自己输入。"
                } else {
                    "还没配模型，任务暂时发不出去。可以回上一步填 Key，或者先进主界面逛逛。"
                },
            )
            Spacer(Modifier.height(AppSpacing.Lg))
            if (modelReady) {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Sm)) {
                    FirstTasks.forEach { task ->
                        TaskExampleRow(task = task, onClick = { start(task) })
                    }
                }
            }
        },
        footer = {
            if (modelReady) {
                PrimaryOnboardingButton(label = "进入主界面", onClick = onFinish)
            } else {
                PrimaryOnboardingButton(label = "回上一步配置模型", onClick = onBackToModel)
                Spacer(Modifier.height(AppSpacing.Sm))
                TextButton(onClick = onFinish) { Text("先这样，直接开始使用") }
            }
        },
    )
}

/** 例子行：一句话 + 右侧「试试」；整行可点（手指够得到的面积比一枚小按钮大） */
@Composable
private fun TaskExampleRow(task: String, onClick: () -> Unit) {
    val buzz = rememberHapticClick()
    Surface(
        shape = RoundedCornerShape(AppRadii.Item),
        color = AppTheme.colors.surfaceRaised,
        border = appBorderStroke(AppSize.Hairline, AppTheme.colors.outlineSoft),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    buzz()
                    onClick()
                }
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
                color = AppTheme.colors.onSurfaceBase,
                modifier = Modifier.weight(1f),
            )
            Text(
                "试试",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
