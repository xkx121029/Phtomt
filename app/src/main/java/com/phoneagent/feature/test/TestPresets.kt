package com.phoneagent.feature.test

/**
 * 内置预设测试方案（英文提示词），参照《HPS ai标准化测试.md》。
 * 每个方案含一组用例；校验规则由 TestEngine 依据用例字段执行。
 */
object TestPresets {

    /** 可选的预设方案列表 */
    val all: List<TestPreset> = listOf(
        presetFormat(),
        presetTargeting(),
        presetDecision(),
        presetMerge(),
        presetRegression(),
        RealScenes.preset,
    )

    /** 组 A：输出格式检查（F01~F10） */
    private fun presetFormat() = TestPreset(
        id = "format",
        name = "组A · 输出格式",
        group = TestGroup.FORMAT,
        description = "校验输出是否为纯 JSON、不含 Markdown、字段齐全且取值合法",
        cases = listOf(
            TestCase(
                id = "F01",
                name = "纯 JSON 输出",
                checkHint = "首字符必须是 { 或 [，末字符必须是 } 或 ]，且是合法 JSON",
                userPrompt = """
                    Page snapshot: {"context_hint":"Search results page","fingerprint":"f_abc123","elements":[{"id":"search_input","type":"EditText","label":"Search","clickable":true,"focused":false},{"id":"btn_submit","type":"Button","label":"Search","clickable":true}]}
                    Task: Search for "coffee" on this page.
                """.trimIndent(),
            ),
            TestCase(
                id = "F02",
                name = "不含 Markdown 标记",
                checkHint = "输出不得包含 ``` 或 json 代码块标记",
                userPrompt = """
                    Page snapshot: {"context_hint":"Detail page","elements":[{"id":"add_cart","label":"Add to cart","clickable":true},{"id":"back","label":"Back","clickable":true}]}
                    Task: Add this item to the cart.
                """.trimIndent(),
            ),
            TestCase(
                id = "F04",
                name = "必填字段齐全",
                checkHint = "包含 type、confidence，且 action 值合法",
                userPrompt = """
                    Page snapshot: {"context_hint":"Home page","elements":[{"id":"tab_orders","label":"Orders","clickable":true}]}
                    Task: Open the Orders tab.
                """.trimIndent(),
            ),
        ),
    )

    /** 组 B：寻址方式检查（B01~B05） */
    private fun presetTargeting() = TestPreset(
        id = "targeting",
        name = "组B · 寻址方式",
        group = TestGroup.TARGETING,
        description = "校验 method 选择：accessibility 用 id/label，screenshot 用坐标",
        cases = listOf(
            TestCase(
                id = "B01",
                name = "accessibility + 有 id → method=id",
                source = "accessibility",
                expectedMethod = "id",
                checkHint = "目标有 id 时必须用 method=id",
                userPrompt = """
                    Source: accessibility element tree.
                    Elements: [{"id":"node_a1","label":"Settings","clickable":true},{"id":"node_a2","label":"Profile","clickable":true}]
                    Task: Tap the Settings entry.
                """.trimIndent(),
            ),
            TestCase(
                id = "B02",
                name = "accessibility + 无 id 有文字 → method=label",
                source = "accessibility",
                expectedMethod = "label",
                checkHint = "目标无 id 但有文字时必须用 method=label",
                userPrompt = """
                    Source: accessibility element tree.
                    Elements: [{"id":"","label":"Confirm Order","clickable":true},{"id":"","label":"Cancel","clickable":true}]
                    Task: Tap the Confirm Order button.
                """.trimIndent(),
            ),
            TestCase(
                id = "B03",
                name = "accessibility 绝不使用 coordinate",
                source = "accessibility",
                forbiddenMethod = "coordinate",
                checkHint = "accessibility 来源不得使用 method=coordinate",
                userPrompt = """
                    Source: accessibility element tree.
                    Elements: [{"id":"node_x","label":"Agree","clickable":true}]
                    Task: Tap the Agree button.
                """.trimIndent(),
            ),
            TestCase(
                id = "B04",
                name = "screenshot → method=coordinate",
                source = "screenshot",
                expectedMethod = "coordinate",
                checkHint = "screenshot 来源必须使用 method=coordinate",
                userPrompt = """
                    Source: screenshot only (no element tree).
                    A search box is at the top of the screen, a submit button at bottom-right.
                    Task: Tap the submit button.
                """.trimIndent(),
            ),
        ),
    )

    /** 组 C：决策逻辑检查（C01、C03、C07、C08、C10） */
    private fun presetDecision() = TestPreset(
        id = "decision",
        name = "组C · 决策逻辑",
        group = TestGroup.DECISION,
        description = "校验关键场景的决策：倒计时广告/m支付确认/成功继续/选第一个/提交",
        cases = listOf(
            TestCase(
                id = "C01",
                name = "倒计时广告 → wait，禁止 tap",
                expectedAction = setOf("wait"),
                forbiddenAction = setOf("tap"),
                checkHint = "context_hint 含倒计时广告标记 → 必须 wait，禁止 tap 跳过",
                userPrompt = """
                    Page snapshot: {"context_hint":"⚠️ Countdown Ad - 5s countdown ad covering the page","elements":[{"id":"btn_skip","label":"Skip 5","clickable":true},{"id":"under","label":"dark","clickable":true}]}
                    Task: Reach the home page (the ad is still counting down).
                """.trimIndent(),
            ),
            TestCase(
                id = "C03",
                name = "支付确认 → tap + needs_user_confirmation=true",
                expectedAction = setOf("tap"),
                checkHint = "支付等不可逆操作必须设 needs_user_confirmation=true",
                userPrompt = """
                    Page snapshot: {"context_hint":"Payment confirmation page","elements":[{"id":"btn_pay","label":"Pay 99.0","clickable":true},{"id":"btn_cancel","label":"Cancel","clickable":true}]}
                    Task: Submit the order and complete payment.
                """.trimIndent(),
            ),
            TestCase(
                id = "C07",
                name = "上一步 ✅ 成功 → 安全继续",
                expectedAction = setOf("tap"),
                forbiddenAction = setOf("wait", "task_complete"),
                checkHint = "上一步已验证成功，应继续下一步，而非重试或 wait",
                userPrompt = """
                    Page snapshot: {"context_hint":"Step 2 of 3 - form page","elements":[{"id":"fld_name","label":"Name","clickable":true},{"id":"btn_next","label":"Next","clickable":true}]}
                    Task: Fill the form. Last step result: ✅ verified (name filled successfully).
                """.trimIndent(),
            ),
            TestCase(
                id = "C08",
                name = "选评分最高 → tap 第一个结果",
                expectedAction = setOf("tap"),
                forbiddenAction = setOf("swipe"),
                checkHint = "用户要选评分最高的 → 应 tap 第一个（最高）结果",
                userPrompt = """
                    Page snapshot: {"context_hint":"Search results list","elements":[{"id":"r1","label":"Restaurant A rating 4.9","clickable":true},{"id":"r2","label":"Restaurant B rating 4.2","clickable":true}]}
                    Task: Choose the highest-rated restaurant.
                """.trimIndent(),
            ),
            TestCase(
                id = "C10",
                name = "购物车已满 → tap 提交订单",
                expectedAction = setOf("tap"),
                checkHint = "购物车已有商品，用户要提交 → 直接 tap 提交订单",
                userPrompt = """
                    Page snapshot: {"context_hint":"Cart page","elements":[{"id":"cart_item","label":"Item x1","clickable":false},{"id":"btn_checkout","label":"Checkout","clickable":true},{"id":"btn_add","label":"Add more","clickable":true}]}
                    Task: Submit the order.
                """.trimIndent(),
            ),
        ),
    )

    /** 组 D：动作合并检查（D01、D04、D05） */
    private fun presetMerge() = TestPreset(
        id = "merge",
        name = "组D · 动作合并",
        group = TestGroup.MERGE,
        description = "校验是否按规则输出 JSON 数组（输入+搜索 / screenshot 禁止 / 跳转禁止）",
        cases = listOf(
            TestCase(
                id = "D01",
                name = "输入框已获焦 + 输入并搜索 → 返回数组",
                source = "accessibility",
                expectArray = true,
                checkHint = "输入框已获焦，输入+搜索可合并为 JSON 数组",
                userPrompt = """
                    Source: accessibility element tree.
                    Elements: [{"id":"search_input","label":"Search","focusable":true,"focused":true},{"id":"btn_submit","label":"Search","clickable":true}]
                    Task: Type "pizza" and search.
                """.trimIndent(),
            ),
            TestCase(
                id = "D04",
                name = "screenshot 来源 → 禁止数组",
                source = "screenshot",
                expectArray = false,
                checkHint = "页面来自截图时不得返回 JSON 数组",
                userPrompt = """
                    Source: screenshot only (no element tree).
                    A search box is focused and a submit button is visible.
                    Task: Type "pizza" and search.
                """.trimIndent(),
            ),
            TestCase(
                id = "D05",
                name = "第一个动作会跳转 → 禁止数组",
                source = "accessibility",
                expectArray = false,
                checkHint = "第一个动作会跳转到新页面时不得返回数组",
                userPrompt = """
                    Source: accessibility element tree.
                    Elements: [{"id":"link_offer","label":"Open offer page","clickable":true}]
                    Task: Open the offer page.
                """.trimIndent(),
            ),
        ),
    )

    /** 组 E：回归基准集（R01、R02、R09） */
    private fun presetRegression() = TestPreset(
        id = "regression",
        name = "组E · 回归基准",
        group = TestGroup.REGRESSION,
        description = "覆盖核心场景，校验输出格式与决策合理性",
        cases = listOf(
            TestCase(
                id = "R01",
                name = "正常点击决策",
                expectedAction = setOf("tap"),
                checkHint = "普通列表页正常点击",
                userPrompt = """
                    Page snapshot: {"context_hint":"Search results page","elements":[{"id":"item1","label":"Store A","clickable":true},{"id":"item2","label":"Store B","clickable":true}]}
                    Task: Open Store A.
                """.trimIndent(),
            ),
            TestCase(
                id = "R02",
                name = "权限弹窗处理",
                expectedAction = setOf("tap"),
                checkHint = "弹窗优先点 Allow/允许",
                userPrompt = """
                    Page snapshot: {"context_hint":"Permission dialog","elements":[{"id":"btn_allow","label":"Allow","clickable":true},{"id":"btn_deny","label":"Deny","clickable":true}]}
                    Task: Continue the previous task (a permission dialog is blocking it).
                """.trimIndent(),
            ),
            TestCase(
                id = "R09",
                name = "任务完成判断",
                expectedAction = setOf("task_complete"),
                checkHint = "订单成功页应判定任务完成",
                userPrompt = """
                    Page snapshot: {"context_hint":"Order placed successfully","elements":[{"id":"order_id","label":"Order #1234","clickable":false}]}
                    Task: The order was placed. Confirm completion.
                """.trimIndent(),
            ),
        ),
    )
}