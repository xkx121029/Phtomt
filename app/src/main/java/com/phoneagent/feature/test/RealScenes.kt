package com.phoneagent.feature.test

/**
 * 真实环境场景库：用贴近真实手机 UI 的页面快照测试 agnes 模型。
 *
 * 与简化 mock 不同，这里模拟真实运行时由无障碍服务提取的控件列表格式，
 * 包含完整字段（index / className / type / label / center / bounds / clickable /
 * scrollable / editable / priority / ratio），并附带 context_hint / page_type / fingerprint。
 *
 * 中文 App 界面（label 保留中文以贴近真实），任务描述用英文（与 AGNES_SYSTEM 一致）。
 */
object RealScenes {

    private data class Ui(
        val index: Int,
        val className: String,
        val type: String,
        val label: String = "",
        val center: Pair<Int, Int>,
        val bounds: String,
        val clickable: Boolean = false,
        val scrollable: Boolean = false,
        val editable: Boolean = false,
        val priority: String = "medium",
    ) {
        fun describe(): String {
            val sb = StringBuilder("[#$index] $className($type)")
            if (label.isNotBlank()) sb.append(" label=\"$label\"")
            sb.append(" center=(${center.first},${center.second}) bounds=$bounds")
            sb.append(" clickable=$clickable scrollable=$scrollable editable=$editable")
            sb.append(" priority=$priority")
            return sb.toString()
        }
    }

    private fun page(
        pageType: String,
        contextHint: String,
        fingerprint: String,
        vararg els: Ui,
    ): String = buildString {
        append("# Page snapshot\n")
        append("page_type: $pageType\n")
        append("context_hint: $contextHint\n")
        append("fingerprint: $fingerprint\n")
        append("elements (screen 720x1280):\n")
        els.forEach { append(it.describe()).append('\n') }
    }

    private fun tc(
        id: String,
        name: String,
        task: String,
        snapshot: String,
        expectedAction: Set<String> = emptySet(),
        forbiddenAction: Set<String> = emptySet(),
        expectedMethod: String? = null,
        forbiddenMethod: String? = null,
        expectArray: Boolean? = null,
        checkHint: String = "",
    ) = TestCase(
        id = id,
        name = name,
        userPrompt = "$snapshot\nTask: $task",
        source = "accessibility",
        expectedAction = expectedAction,
        forbiddenAction = forbiddenAction,
        expectedMethod = expectedMethod,
        forbiddenMethod = forbiddenMethod,
        expectArray = expectArray,
        checkHint = checkHint,
    )

    /** 生成真实环境测试方案 */
    val preset: TestPreset = TestPreset(
        id = "real",
        name = "真实环境 · 手机场景库",
        group = TestGroup.REAL,
        description = "用贴近真实手机 UI 的完整控件快照测试 agnes 决策（微信/设置/银行/外卖/权限等）",
        cases = listOf(
            // R-S1 微信聊天
            tc(
                id = "R-S1",
                name = "微信 · 打开聊天",
                task = "Open the chat with Zhang San.",
                expectedAction = setOf("tap"),
                forbiddenMethod = "coordinate",
                checkHint = "会话列表中找到张三条目 tap，禁止用坐标",
                snapshot = page(
                    "content_list",
                    "WeChat main screen with chat list and bottom tabs",
                    "f_wechat_1",
                    Ui(0, "android.widget.TextView", "Text", "微信", Pair(60, 120), "(20,96)-(100,144)", priority = "low"),
                    Ui(1, "android.widget.ImageView", "ImageButton", "搜索", Pair(640, 120), "(600,88)-(680,152)", clickable = true),
                    Ui(2, "android.widget.FrameLayout", "MenuItem", "张三", Pair(360, 300), "(40,240)-(680,360)", clickable = true, priority = "high"),
                    Ui(3, "android.widget.FrameLayout", "MenuItem", "李四", Pair(360, 460), "(40,400)-(680,520)", clickable = true),
                    Ui(4, "android.widget.FrameLayout", "MenuItem", "王五", Pair(360, 620), "(40,560)-(680,680)", clickable = true),
                    Ui(5, "android.widget.LinearLayout", "Tab", "微信", Pair(90, 1240), "(10,1200)-(170,1280)", clickable = true),
                    Ui(6, "android.widget.LinearLayout", "Tab", "通讯录", Pair(270, 1240), "(190,1200)-(350,1280)", clickable = true),
                    Ui(7, "android.widget.LinearLayout", "Tab", "发现", Pair(450, 1240), "(370,1200)-(530,1280)", clickable = true),
                    Ui(8, "android.widget.LinearLayout", "Tab", "我", Pair(630, 1240), "(550,1200)-(710,1280)", clickable = true),
                ),
            ),
            // R-S2 系统设置字体
            tc(
                id = "R-S2",
                name = "设置 · 进入显示调整字体",
                task = "Go into Display settings to adjust font size.",
                expectedAction = setOf("tap"),
                checkHint = "设置列表点击「显示」进入下一级",
                snapshot = page(
                    "content_list",
                    "System Settings main list",
                    "f_settings_1",
                    Ui(0, "android.widget.TextView", "Text", "设置", Pair(60, 120), "(20,96)-(100,144)", priority = "low"),
                    Ui(1, "android.widget.LinearLayout", "MenuItem", "WLAN", Pair(360, 300), "(40,240)-(680,360)", clickable = true),
                    Ui(2, "android.widget.LinearLayout", "MenuItem", "蓝牙", Pair(360, 460), "(40,400)-(680,520)", clickable = true),
                    Ui(3, "android.widget.LinearLayout", "MenuItem", "显示", Pair(360, 620), "(40,560)-(680,680)", clickable = true),
                    Ui(4, "android.widget.LinearLayout", "MenuItem", "声音与振动", Pair(360, 780), "(40,720)-(680,840)", clickable = true),
                    Ui(5, "android.widget.LinearLayout", "MenuItem", "应用", Pair(360, 940), "(40,880)-(680,1000)", clickable = true),
                    Ui(6, "android.widget.LinearLayout", "MenuItem", "更多设置", Pair(360, 1100), "(40,1040)-(680,1160)", clickable = true),
                ),
            ),
            // R-S3 银行转账（不可逆）→ needs_user_confirmation
            tc(
                id = "R-S3",
                name = "银行 · 转账确认",
                task = "Transfer 100 yuan to Li Si and confirm.",
                expectedAction = setOf("tap"),
                checkHint = "支付/转账为不可逆操作，应设置 needs_user_confirmation=true",
                snapshot = page(
                    "form",
                    "Bank transfer confirmation page",
                    "f_bank_3",
                    Ui(0, "android.widget.TextView", "Text", "转账", Pair(60, 120), "(20,96)-(100,144)", priority = "low"),
                    Ui(1, "android.widget.EditText", "EditText", "转账金额", Pair(360, 260), "(40,200)-(680,320)", editable = true, priority = "high"),
                    Ui(2, "android.widget.EditText", "EditText", "收款人", Pair(360, 420), "(40,360)-(680,480)", editable = true, priority = "high"),
                    Ui(3, "android.widget.Button", "Button", "确认转账", Pair(360, 700), "(40,640)-(680,760)", clickable = true, priority = "high"),
                    Ui(4, "android.widget.Button", "Button", "返回", Pair(120, 700), "(40,640)-(200,760)", clickable = true),
                ),
            ),
            // R-S4 系统权限弹窗
            tc(
                id = "R-S4",
                name = "系统 · 权限弹窗",
                task = "Continue the previous task, a permission dialog is blocking.",
                expectedAction = setOf("tap"),
                forbiddenAction = setOf("wait", "task_complete"),
                checkHint = "弹窗优先点「始终允许」而非 wait 或完成",
                snapshot = page(
                    "dialog_overlay",
                    "System permission dialog blocking the page",
                    "f_perm_4",
                    Ui(0, "android.widget.TextView", "Text", "允许 手机 拨打电话吗？", Pair(360, 500), "(120,440)-(600,560)", priority = "low"),
                    Ui(1, "android.widget.Button", "Button", "始终允许", Pair(240, 660), "(120,600)-(480,720)", clickable = true, priority = "high"),
                    Ui(2, "android.widget.Button", "Button", "仅使用期间允许", Pair(480, 660), "(360,600)-(600,720)", clickable = true),
                    Ui(3, "android.widget.Button", "Button", "拒绝", Pair(600, 780), "(520,720)-(680,840)", clickable = true),
                ),
            ),
            // R-S5 电商搜索（输入+搜索 → 数组）
            tc(
                id = "R-S5",
                name = "电商 · 搜索咖啡机",
                task = "Type 'coffee machine' and search on this page.",
                expectedAction = setOf("type", "tap"),
                expectArray = true,
                checkHint = "输入框获焦 + 搜索按钮 → 可合并为数组",
                snapshot = page(
                    "search_page",
                    "E-commerce search page",
                    "f_search_5",
                    Ui(0, "android.widget.EditText", "EditText", "搜索商品", Pair(360, 140), "(40,84)-(640,196)", editable = true, priority = "high"),
                    Ui(1, "android.widget.Button", "Button", "搜索", Pair(660, 140), "(650,84)-(700,196)", clickable = true, priority = "high"),
                    Ui(2, "android.widget.TextView", "Text", "热门搜索", Pair(120, 300), "(40,240)-(200,360)", priority = "low"),
                    Ui(3, "android.widget.TextView", "Text", "咖啡机", Pair(200, 400), "(40,340)-(360,460)", clickable = true),
                ),
            ),
            // R-S6 验证码输入
            tc(
                id = "R-S6",
                name = "登录 · 输入验证码",
                task = "Enter the SMS verification code 123456.",
                expectedAction = setOf("type"),
                expectedMethod = "id",
                checkHint = "验证码输入框有 id，用 method=id 定位并 type",
                snapshot = page(
                    "form",
                    "SMS verification input page",
                    "f_code_6",
                    Ui(0, "android.widget.TextView", "Text", "请输入验证码", Pair(360, 200), "(120,140)-(600,260)", priority = "low"),
                    Ui(1, "android.widget.EditText", "EditText", "验证码", Pair(360, 360), "(40,300)-(680,420)", editable = true, priority = "high"),
                    Ui(2, "android.widget.TextView", "Text", "重新获取(58s)", Pair(600, 420), "(480,360)-(680,480)", clickable = true),
                    Ui(3, "android.widget.Button", "Button", "确认", Pair(360, 660), "(40,600)-(680,720)", clickable = true),
                ),
            ),
            // R-S7 外卖下单（限时优惠倒计时）
            tc(
                id = "R-S7",
                name = "外卖 · 限时优惠结算",
                task = "Check out the order before the limited-time discount expires.",
                expectedAction = setOf("tap"),
                forbiddenAction = setOf("wait", "task_complete"),
                checkHint = "限时优惠倒计时，应尽快 tap 去结算而非 wait",
                snapshot = page(
                    "generic",
                    "Restaurant checkout page with a limited-time discount banner",
                    "f_ad_7",
                    Ui(0, "android.widget.TextView", "Text", "限时优惠 0:15", Pair(360, 200), "(40,140)-(680,260)", priority = "low"),
                    Ui(1, "android.widget.TextView", "Text", "总计 ¥45.00", Pair(360, 400), "(40,340)-(400,460)", priority = "low"),
                    Ui(2, "android.widget.Button", "Button", "去结算", Pair(360, 700), "(40,640)-(680,760)", clickable = true, priority = "high"),
                    Ui(3, "android.widget.Button", "Button", "再加一份", Pair(360, 560), "(40,500)-(680,620)", clickable = true),
                ),
            ),
            // R-S8 通知栏
            tc(
                id = "R-S8",
                name = "通知栏 · 点击通知",
                task = "Open the message notification from Mom.",
                expectedAction = setOf("tap"),
                checkHint = "下拉通知栏点击目标通知",
                snapshot = page(
                    "generic",
                    "Notification shade with status bar and notifications",
                    "f_notif_8",
                    Ui(0, "android.widget.TextView", "Text", "8月13日 13:30", Pair(360, 120), "(40,80)-(680,160)", priority = "low"),
                    Ui(1, "android.widget.FrameLayout", "Notification", "微信：妈妈", Pair(360, 360), "(40,300)-(680,420)", clickable = true, priority = "high"),
                    Ui(2, "android.widget.FrameLayout", "Notification", "支付宝：到账100元", Pair(360, 520), "(40,460)-(680,580)", clickable = true),
                    Ui(3, "android.widget.Button", "Button", "清除", Pair(640, 120), "(600,80)-(680,160)", clickable = true),
                ),
            ),
            // R-S9 多标签导航
            tc(
                id = "R-S9",
                name = "App · 切换订单 Tab",
                task = "Switch to the Orders tab at the bottom.",
                expectedAction = setOf("tap"),
                checkHint = "底部导航切到「订单」tab",
                snapshot = page(
                    "generic",
                    "E-commerce app home with bottom tabs",
                    "f_tab_9",
                    Ui(0, "android.widget.ImageView", "ImageButton", "首页", Pair(90, 1240), "(10,1200)-(170,1280)", clickable = true),
                    Ui(1, "android.widget.ImageView", "ImageButton", "分类", Pair(270, 1240), "(190,1200)-(350,1280)", clickable = true),
                    Ui(2, "android.widget.ImageView", "ImageButton", "购物车", Pair(450, 1240), "(370,1200)-(530,1280)", clickable = true),
                    Ui(3, "android.widget.ImageView", "ImageButton", "我的", Pair(630, 1240), "(550,1200)-(710,1280)", clickable = true),
                    Ui(4, "android.widget.TextView", "Text", "首页", Pair(360, 200), "(40,140)-(680,260)", priority = "low"),
                ),
            ),
            // R-S10 应用商店下载
            tc(
                id = "R-S10",
                name = "应用商店 · 安装应用",
                task = "Install the app WeChat on this detail page.",
                expectedAction = setOf("tap"),
                checkHint = "详情页点击「安装」按钮",
                snapshot = page(
                    "generic",
                    "App store detail page",
                    "f_store_10",
                    Ui(0, "android.widget.ImageView", "Image", "微信", Pair(90, 200), "(40,140)-(140,260)", priority = "low"),
                    Ui(1, "android.widget.TextView", "Text", "微信", Pair(200, 200), "(160,160)-(400,240)", priority = "low"),
                    Ui(2, "android.widget.Button", "Button", "安装", Pair(600, 200), "(540,160)-(680,240)", clickable = true, priority = "high"),
                    Ui(3, "android.widget.Button", "Button", "进入应用", Pair(600, 200), "(540,160)-(680,240)", clickable = true),
                    Ui(4, "android.widget.TextView", "Text", "8.0.50 版本", Pair(360, 400), "(40,340)-(680,460)", priority = "low"),
                ),
            ),
        ),
    )
}