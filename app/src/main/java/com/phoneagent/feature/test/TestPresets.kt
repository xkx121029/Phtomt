package com.phoneagent.feature.test

/**
 * 内置预设测试方案（英文提示词），参照《HPS ai标准化测试.md》。
 * 每个方案含一组用例；校验规则由 TestEngine 依据用例字段执行。
 *
 * 期望值按「转译层」的 intent 协议填写：
 * - expectedIntent / forbiddenIntent → AI 的 `intent` 取值（不再用 type/action）
 * - expectedBy / forbiddenBy → `target.by` 取值 id / text / hint / coordinate（不再用 method）
 * - requireConfirmation → 不可逆操作必须带 `needs_confirmation=true`
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

    /** 组 A：输出格式检查（F01~F04） */
    private fun presetFormat() = TestPreset(
        id = "format",
        name = "组A · 输出格式",
        group = TestGroup.FORMAT,
        description = "校验输出是否为纯 JSON、不含 Markdown、字段名为 intent 且统一字段齐全",
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
                id = "F03",
                name = "字段名必须为 intent",
                checkHint = "顶层字段只能是 intent，禁止输出 type/action 或扁平 by/value（转译层无法识别）",
                userPrompt = """
                    Page snapshot: {"context_hint":"Detail page","elements":[{"id":"btn_share","label":"Share","clickable":true}]}
                    Task: Share this item.
                """.trimIndent(),
            ),
            TestCase(
                id = "F04",
                name = "统一必填字段齐全",
                checkHint = "必须同时给出 intent / reasoning / expected / confidence（confidence 范围 0~1）",
                requireAllFields = true,
                userPrompt = """
                    Page snapshot: {"context_hint":"Home page","elements":[{"id":"tab_orders","label":"Orders","clickable":true}]}
                    Task: Open the Orders tab.
                """.trimIndent(),
            ),
        ),
    )

    /** 组 B：寻址方式检查（B01~B04） */
    private fun presetTargeting() = TestPreset(
        id = "targeting",
        name = "组B · 寻址方式",
        group = TestGroup.TARGETING,
        description = "校验 target.by 选择：有 id 用 id、有文字用 text、图片用 hint，坐标仅作兜底",
        cases = listOf(
            TestCase(
                id = "B01",
                name = "accessibility + 有 id → by=id",
                source = "accessibility",
                expectedBy = "id",
                checkHint = "目标有 id（或 semantic_id）时必须用 by=id",
                userPrompt = """
                    Source: accessibility element tree.
                    Elements: [{"id":"node_a1","label":"Settings","clickable":true},{"id":"node_a2","label":"Profile","clickable":true}]
                    Task: Tap the Settings entry.
                """.trimIndent(),
            ),
            TestCase(
                id = "B02",
                name = "accessibility + 无 id 有文字 → by=text",
                source = "accessibility",
                expectedBy = "text",
                checkHint = "目标无 id 但有可读文字时必须用 by=text",
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
                forbiddenBy = "coordinate",
                checkHint = "元素树可读时不得用 by=coordinate 硬点（坐标只允许最后兜底）",
                userPrompt = """
                    Source: accessibility element tree.
                    Elements: [{"id":"node_x","label":"Agree","clickable":true}]
                    Task: Tap the Agree button.
                """.trimIndent(),
            ),
            TestCase(
                id = "B04",
                name = "screenshot 无元素树 → 不得编 id",
                source = "screenshot",
                forbiddenBy = "id",
                checkHint = "页面只有截图时必须 by=hint（端侧视觉定位）或 by=coordinate 兜底，禁止编造 id",
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
        description = "校验关键场景的决策：倒计时广告/支付确认/成功继续/选第一个/提交",
        cases = listOf(
            TestCase(
                id = "C01",
                name = "倒计时广告 → wait，禁止点击",
                expectedIntent = setOf("wait"),
                forbiddenIntent = setOf("tap", "close"),
                checkHint = "context_hint 含倒计时广告标记 → 必须 wait，绝对禁止 tap / close（close 会点到「跳过」）",
                userPrompt = """
                    Page snapshot: {"context_hint":"⚠️ Countdown Ad - 5s countdown ad covering the page","elements":[{"id":"btn_skip","label":"Skip 5","clickable":true},{"id":"under","label":"dark","clickable":true}]}
                    Task: Reach the home page (the ad is still counting down).
                """.trimIndent(),
            ),
            TestCase(
                id = "C03",
                name = "支付确认 → tap + needs_confirmation=true",
                expectedIntent = setOf("tap"),
                requireConfirmation = true,
                checkHint = "支付等不可逆操作必须设 needs_confirmation=true",
                userPrompt = """
                    Page snapshot: {"context_hint":"Payment confirmation page","elements":[{"id":"btn_pay","label":"Pay 99.0","clickable":true},{"id":"btn_cancel","label":"Cancel","clickable":true}]}
                    Task: Submit the order and complete payment.
                """.trimIndent(),
            ),
            TestCase(
                id = "C07",
                name = "上一步 ✅ 成功 → 安全继续",
                expectedIntent = setOf("tap"),
                forbiddenIntent = setOf("wait", "finish"),
                checkHint = "上一步已验证成功，应继续下一步，而非重试、wait 或提前 finish",
                userPrompt = """
                    Page snapshot: {"context_hint":"Step 2 of 3 - form page","elements":[{"id":"fld_name","label":"Name","clickable":true},{"id":"btn_next","label":"Next","clickable":true}]}
                    Task: Fill the form. Last step result: ✅ verified (name filled successfully).
                """.trimIndent(),
            ),
            TestCase(
                id = "C08",
                name = "选评分最高 → tap 第一个结果",
                expectedIntent = setOf("tap"),
                forbiddenIntent = setOf("swipe"),
                checkHint = "用户要选评分最高的 → 应 tap 第一个（最高）结果",
                userPrompt = """
                    Page snapshot: {"context_hint":"Search results list","elements":[{"id":"r1","label":"Restaurant A rating 4.9","clickable":true},{"id":"r2","label":"Restaurant B rating 4.2","clickable":true}]}
                    Task: Choose the highest-rated restaurant.
                """.trimIndent(),
            ),
            TestCase(
                id = "C10",
                name = "购物车已满 → tap 提交订单",
                expectedIntent = setOf("tap"),
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
        description = "校验是否按规则输出 JSON 数组（input+search / screenshot 禁止 / 跳转禁止）",
        cases = listOf(
            TestCase(
                id = "D01",
                name = "输入框已获焦 + 输入并搜索 → 返回数组",
                source = "accessibility",
                expectArray = true,
                checkHint = "输入框已获焦，input+search 可合并为 JSON 数组（最多 2 个意图）",
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
                expectedIntent = setOf("tap"),
                checkHint = "普通列表页正常点击",
                userPrompt = """
                    Page snapshot: {"context_hint":"Search results page","elements":[{"id":"item1","label":"Store A","clickable":true},{"id":"item2","label":"Store B","clickable":true}]}
                    Task: Open Store A.
                """.trimIndent(),
            ),
            TestCase(
                id = "R02",
                name = "权限弹窗处理",
                expectedIntent = setOf("tap", "confirm"),
                checkHint = "弹窗优先允许：可直接用 confirm 语义意图，也可 tap 允许按钮",
                userPrompt = """
                    Page snapshot: {"context_hint":"Permission dialog","elements":[{"id":"btn_allow","label":"Allow","clickable":true},{"id":"btn_deny","label":"Deny","clickable":true}]}
                    Task: Continue the previous task (a permission dialog is blocking it).
                """.trimIndent(),
            ),
            TestCase(
                id = "R09",
                name = "任务完成判断",
                expectedIntent = setOf("finish"),
                checkHint = "订单成功页有明确达成证据时应判定 finish（并在 summary 写明证据）",
                userPrompt = """
                    Page snapshot: {"context_hint":"Order placed successfully","elements":[{"id":"order_id","label":"Order #1234","clickable":false}]}
                    Task: The order was placed. Confirm completion.
                """.trimIndent(),
            ),
        ),
    )
}