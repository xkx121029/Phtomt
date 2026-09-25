package com.phoneagent.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.ChartLine
import com.composables.icons.lucide.ArrowUpDown
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CircleCheckBig
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.CloudUpload
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.CornerDownLeft
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.FileDown
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.FlaskConical
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Hand
import com.composables.icons.lucide.History
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Keyboard
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.ListFilter
import com.composables.icons.lucide.ListTodo
import com.composables.icons.lucide.LoaderCircle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.MousePointerClick
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Pointer
import com.composables.icons.lucide.RefreshCcw
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Rocket
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.ScanSearch
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.SmartphoneNfc
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Store
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Trash
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.User
import com.composables.icons.lucide.Wifi
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Zap

/**
 * 全项目唯一图标间接层。
 *
 * 为什么要有这一层：
 * 页面直接引用第三方图标库（如原来的 `Icons.Rounded.*`）会把「语义」和「某个库的具体图形」绑死，
 * 换图标集时要改几十个文件。这里用 AppIcons 这个名字层隔开：
 * 业务代码只说「我要一个删除图标」，具体是 Lucide 还是别的库，只在本文件里决定。
 *
 * 图标来源：Lucide（https://lucide.dev），线性描边风格，ISC 许可，可商用。
 * 命名沿用业务语义（Play / Delete / ChevronDown），不照抄底层库的名字。
 *
 * 用法：`Icon(AppIcons.Delete, contentDescription = "删除")`
 */
object AppIcons {

    // ── 导航与方向 ──────────────────────────────────────────────
    /** 返回（原 Icons.AutoMirrored.Filled.ArrowBack） */
    val ArrowBack: ImageVector get() = Lucide.ArrowLeft
    val ChevronRight: ImageVector get() = Lucide.ChevronRight
    val ChevronDown: ImageVector get() = Lucide.ChevronDown
    val ChevronUp: ImageVector get() = Lucide.ChevronUp
    /** 展开全屏（原 Icons.Rounded.OpenInFull） */
    val Expand: ImageVector get() = Lucide.Maximize

    // ── 基础操作 ────────────────────────────────────────────────
    val Add: ImageVector get() = Lucide.Plus
    val Close: ImageVector get() = Lucide.X
    val Cancel: ImageVector get() = Lucide.CircleX
    val CheckCircle: ImageVector get() = Lucide.CircleCheck
    /** 任务完成（原 Icons.Rounded.TaskAlt） */
    val TaskAlt: ImageVector get() = Lucide.CircleCheckBig
    val Delete: ImageVector get() = Lucide.Trash2
    val DeleteOutline: ImageVector get() = Lucide.Trash
    val Edit: ImageVector get() = Lucide.Pencil
    val Search: ImageVector get() = Lucide.Search
    val Refresh: ImageVector get() = Lucide.RefreshCw
    val Sync: ImageVector get() = Lucide.RefreshCcw
    val Reorder: ImageVector get() = Lucide.GripVertical
    val FilterList: ImageVector get() = Lucide.ListFilter
    val More: ImageVector get() = Lucide.EllipsisVertical
    /** 三横线 / 抽屉入口（原 Icons.Rounded.Menu） */
    val Menu: ImageVector get() = Lucide.Menu

    // ── 运行控制 ────────────────────────────────────────────────
    val Play: ImageVector get() = Lucide.Play
    val Stop: ImageVector get() = Lucide.Square
    val Send: ImageVector get() = Lucide.Send
    val Loading: ImageVector get() = Lucide.LoaderCircle

    // ── 文件与文档 ──────────────────────────────────────────────
    val Folder: ImageVector get() = Lucide.Folder
    val FolderOpen: ImageVector get() = Lucide.FolderOpen
    /** 文档（原 Icons.Rounded.Description） */
    val Description: ImageVector get() = Lucide.FileText
    /** 导出文件（原 Icons.Rounded.FileDownload） */
    val FileDownload: ImageVector get() = Lucide.FileDown
    /** 备份（原 Icons.Filled.Backup） */
    val Backup: ImageVector get() = Lucide.CloudUpload
    val Code: ImageVector get() = Lucide.Code

    // ── 状态与提示 ──────────────────────────────────────────────
    /** 错误提示（原 Icons.Rounded.ErrorOutline） */
    val ErrorOutline: ImageVector get() = Lucide.CircleAlert
    /** 报告（原 Icons.Rounded.Report） */
    val Report: ImageVector get() = Lucide.TriangleAlert
    val Info: ImageVector get() = Lucide.Info
    val Star: ImageVector get() = Lucide.Star

    // ── 功能模块 ────────────────────────────────────────────────
    val Home: ImageVector get() = Lucide.House
    val Settings: ImageVector get() = Lucide.Settings
    val Memory: ImageVector get() = Lucide.Cpu
    val Person: ImageVector get() = Lucide.User
    val Key: ImageVector get() = Lucide.KeyRound
    /** 终端 / 命令（原 Icons.Filled.Terminal） */
    val Terminal: ImageVector get() = Lucide.Terminal
    /** 闪电 / 自动化（原 Icons.Filled.Bolt） */
    val Bolt: ImageVector get() = Lucide.Zap
    /** 智能体（原 Icons.Filled.SmartToy） */
    val SmartToy: ImageVector get() = Lucide.Bot
    /** 灵感 / 生成（原 Icons.Rounded.AutoAwesome） */
    val AutoAwesome: ImageVector get() = Lucide.Sparkles
    /** 实验 / 测试（原 Icons.Rounded.Science） */
    val Science: ImageVector get() = Lucide.FlaskConical
    /** 洞察 / 趋势（原 Icons.Rounded.Insights） */
    val Insights: ImageVector get() = Lucide.ChartLine
    /** 历史记录（原 Icons.Rounded.History） */
    val History: ImageVector get() = Lucide.History
    /** 应用商店（原 Icons.Rounded.Storefront） */
    val Store: ImageVector get() = Lucide.Store
    /** 清单 / 任务队列 */
    val ListTodo: ImageVector get() = Lucide.ListTodo

    // ── 权限与设备 ──────────────────────────────────────────────
    /** 无障碍 / 触控（原 Icons.Rounded.TouchApp） */
    val TouchApp: ImageVector get() = Lucide.Pointer
    /** 悬浮窗 / 相机（原 Icons.Rounded.CameraAlt） */
    val Camera: ImageVector get() = Lucide.Camera
    /** 无线 ADB（原 Icons.Filled.Wifi） */
    val Wifi: ImageVector get() = Lucide.Wifi

    /** 内置浏览器（AI 上网、操作网页） */
    val Globe: ImageVector get() = Lucide.Globe
    /** 投送到设备（原 Icons.Rounded.SendToMobile） */
    val SendToMobile: ImageVector get() = Lucide.SmartphoneNfc
    /** 翻译 / 双语（原 Icons.Rounded.Translate） */
    val Translate: ImageVector get() = Lucide.Languages
    /** 画面预览 */
    val Preview: ImageVector get() = Lucide.Eye

    // ── 工具动作（Agent 页"调用了什么工具"的图标语言） ──────────────
    /** 点击 / 轻触 */
    val Tap: ImageVector get() = Lucide.MousePointerClick
    /** 长按 */
    val LongPress: ImageVector get() = Lucide.Hand
    /** 键盘输入 */
    val Keyboard: ImageVector get() = Lucide.Keyboard
    /** 回车 / 按键 */
    val EnterKey: ImageVector get() = Lucide.CornerDownLeft
    /** 滑动 / 滚动 */
    val ScrollVertical: ImageVector get() = Lucide.ArrowUpDown
    /** 滚动查找 */
    val ScrollSearch: ImageVector get() = Lucide.ScanSearch
    /** 启动应用 */
    val Launch: ImageVector get() = Lucide.Rocket
    /** 记住（写入长期记忆） */
    val Remember: ImageVector get() = Lucide.Save

    // ── 动作模式档位（输入栏下方那条切换条） ──────────────────────
    /** 保守档：护盾——授权范围收窄 */
    val Guard: ImageVector get() = Lucide.Shield
    /** 均衡档：滑杆——范围可调 */
    val Tune: ImageVector get() = Lucide.SlidersHorizontal
}