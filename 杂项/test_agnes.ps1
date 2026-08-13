# agnes-2.5-flash 标准化测试脚本（英文提示词，符合《HPS ai标准化测试.md》字段）
# 用法：powershell -ExecutionPolicy Bypass -File test_agnes.ps1

$system = @'
You are Phantom, an Android automation agent being regression-tested.
# Output rules
1. Output ONLY one JSON. First char MUST be { or [, last MUST be } or ].
2. NEVER output markdown modifiers or extra text.
# Action object fields
- action: one of tap|long_press|swipe|type|key|wait|launch|scroll_to|abort|task_complete
- target.method: id|label|coordinate (accessibility source -> id/label; screenshot source -> coordinate)
- confidence: a number 0~1
- needs_user_confirmation: true for irreversible actions (payment).
- If context_hint contains "Countdown Ad" -> output action wait, NEVER tap.
# Action merging
You may output a JSON ARRAY [a, b] of up to 2 actions ONLY when: page source is accessibility AND the first action will NOT navigate away. NEVER use an array for screenshot source or when the first action navigates.
'@

$apiKey = "sk-YE66lIC0LsqN20JO52yWYC7j9WCVBE9BFvRTA7ianpVFo9pq"
$uri = "https://api.agnes-ai.cn/v1/chat/completions"

$cases = @(
  @{ id = "C01"; user = 'Page snapshot: {"context_hint":"⚠️ Countdown Ad - 5s countdown ad covering the page","elements":[{"id":"btn_skip","label":"Skip 5","clickable":true}]} Task: reach the home page while the ad is still counting down.' }
  @{ id = "B01"; user = 'Source: accessibility element tree. Elements: [{"id":"node_a1","label":"Settings","clickable":true},{"id":"node_a2","label":"Profile","clickable":true}] Task: tap the Settings entry.' }
  @{ id = "B04"; user = 'Source: screenshot only (no element tree). A search box is at top, a submit button at bottom-right. Task: tap the submit button.' }
  @{ id = "C03"; user = 'Page snapshot: {"context_hint":"Payment confirmation page","elements":[{"id":"btn_pay","label":"Pay 99.0","clickable":true}]} Task: submit the order and complete payment.' }
  @{ id = "D01"; user = 'Source: accessibility element tree. Elements: [{"id":"search_input","label":"Search","focused":true},{"id":"btn_submit","label":"Search","clickable":true}] Task: type "pizza" and search.' }
  @{ id = "D04"; user = 'Source: screenshot only (no element tree). A search box is focused and a submit button is visible. Task: type "pizza" and search.' }
  @{ id = "R09"; user = 'Page snapshot: {"context_hint":"Order placed successfully","elements":[{"id":"order_id","label":"Order #1234","clickable":false}]} Task: the order was placed, confirm completion.' }
)

foreach ($c in $cases) {
    $body = @{
        model       = "agnes-2.5-flash"
        temperature = 0.1
        messages    = @(
            @{ role = "system"; content = $system },
            @{ role = "user"; content = $c.user }
        )
    } | ConvertTo-Json -Depth 6
    try {
        $r = Invoke-RestMethod -Uri $uri -Method Post -Headers @{ Authorization = "Bearer $apiKey" } -ContentType "application/json" -Body $body
        Write-Output "== $($c.id) =="
        Write-Output $r.choices[0].message.content
    } catch {
        Write-Output "== $($c.id) ERROR: $($_.Exception.Message) =="
    }
}