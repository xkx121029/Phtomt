# Phantom AI Agent 标准化测试流程文档（电脑端）

**版本**：v1.0
**日期**：2026-08-12
**适用范围**：Phantom v2.0 开发迭代中可在电脑上执行的全部 AI 测试

## 一、测试范围

本文件仅包含**不依赖真机、可在开发电脑上直接运行**的测试。需要真机的端到端测试和异常注入测试不在本文档范围内。

| 测试层             | 可在电脑上跑？  | 依赖           |
| --------------- | -------- | ------------ |
| 第一层：端侧决策引擎单元测试  | ✅ 是      | JUnit，纯本地    |
| 第二层：Prompt 回归测试 | ✅ 是      | 云端 API（HTTP） |
| 第三层：单场景端到端测试    | ❌ 否（需真机） | —            |
| 第四层：异常注入与边界测试   | ❌ 否（需真机） | —            |

## 二、第一层：端侧决策引擎单元测试

### 2.1 测试目标

验证 `LocalDecisionEngine` 的五分类逻辑在各类场景下判断正确。该模块是纯规则匹配，不依赖 Android 框架、不依赖真机、不依赖云端。JUnit 直接跑。

### 2.2 测试用例

#### 弹窗类（DIALOG）

| 编号  | 场景         | 输入控件                 | 预期分类   | 预期动作        |
| --- | ---------- | -------------------- | ------ | ----------- |
| D01 | 系统权限弹窗     | 两个按钮 "允许" "拒绝"       | DIALOG | tap "允许"    |
| D02 | 仅关闭按钮的弹窗   | 关闭图标 + 文字 "领取优惠券"    | DIALOG | tap 关闭按钮    |
| D03 | 确认对话框      | "确定" "取消" + "确定退出吗？" | DIALOG | tap "确定"    |
| D04 | "我知道了" 类弹窗 | 只有一个 "我知道了" 按钮       | DIALOG | tap "我知道了"  |
| D05 | "以后再说" 类弹窗 | "立即体验" "以后再说"        | DIALOG | tap "立即体验"  |
| D06 | 否定式弹窗      | "残忍拒绝" "立即领取"        | DIALOG | tap "立即领取"  |
| D07 | 英语弹窗       | "Allow" "Deny"       | DIALOG | tap "Allow" |

#### 加载中类（LOADING）

| 编号  | 场景           | 输入控件                       | 预期分类    | 预期动作        |
| --- | ------------ | -------------------------- | ------- | ----------- |
| L01 | 标准加载页        | ProgressBar + "加载中..."     | LOADING | wait 2500ms |
| L02 | 请稍候页         | "请稍候，正在加载数据"               | LOADING | wait 2500ms |
| L03 | 控件极少（可能未渲染完） | 仅 1 个 FrameLayout，无可交互控件   | LOADING | wait 2000ms |
| L04 | 英语加载页        | "Loading..." + ProgressBar | LOADING | wait 2500ms |

#### 异常页类（ERROR）

| 编号  | 场景      | 输入控件                      | 预期分类  | 预期动作        |
| --- | ------- | ------------------------- | ----- | ----------- |
| E01 | 网络异常有重试 | "网络异常，请检查网络" + "点击重试" 按钮  | ERROR | tap "点击重试"  |
| E02 | 网络异常无重试 | "加载失败，请检查网络连接"，无按钮        | ERROR | key BACK    |
| E03 | 加载失败有重试 | "加载失败" + "重试" 按钮          | ERROR | tap "重试"    |
| E04 | 英语错误页   | "Network error" + "Retry" | ERROR | tap "Retry" |

#### 任务完成类（COMPLETED）

| 编号  | 场景    | 输入控件                        | 预期分类      | 预期动作           |
| --- | ----- | --------------------------- | --------- | -------------- |
| C01 | 下单成功  | "下单成功！" + 订单详情              | COMPLETED | task\_complete |
| C02 | 支付成功  | "支付成功" + 金额                 | COMPLETED | task\_complete |
| C03 | 发送成功  | "发送成功"                      | COMPLETED | task\_complete |
| C04 | 英语完成页 | "Order placed successfully" | COMPLETED | task\_complete |

#### 正常页面类（NORMAL\_PAGE —— 上报云端，不本地决策）

| 编号  | 场景     | 输入控件                  | 预期分类         | 预期动作 |
| --- | ------ | --------------------- | ------------ | ---- |
| N01 | 搜索结果列表 | 搜索框 + 多个结果卡片          | NORMAL\_PAGE | null |
| N02 | 商品详情页  | 商品图片 + "加入购物车" + 规格选择 | NORMAL\_PAGE | null |
| N03 | 购物车页   | 商品列表 + "结算" 按钮        | NORMAL\_PAGE | null |
| N04 | 聊天页面   | 消息列表 + 输入框 + 发送按钮     | NORMAL\_PAGE | null |
| N05 | 设置页面   | 多个设置项 + 开关控件          | NORMAL\_PAGE | null |

#### 边界/容易误判类

| 编号  | 场景               | 输入控件                  | 预期分类         | 说明                 |
| --- | ---------------- | --------------------- | ------------ | ------------------ |
| B01 | 购物车有 "确认下单"      | 多个商品 + 总价 + "确认下单" 按钮 | NORMAL\_PAGE | 不应因为 "确认" 关键词误判为弹窗 |
| B02 | 页面上有 "成功" 但非完成任务 | 列表中有 "已完成" 的历史订单项     | NORMAL\_PAGE | 不应误判为 COMPLETED    |
| B03 | 页面上有 "重试" 但非错误页  | 游戏中的 "重试本关"           | NORMAL\_PAGE | 不应误判为 ERROR        |

### 2.3 验收标准

- 全部 D01\~D07 通过（弹窗识别准确率 100%）
- 全部 L01\~L04 通过（加载识别准确率 100%）
- 全部 E01\~E04 通过（异常识别准确率 100%）
- 全部 C01\~C04 通过（完成识别准确率 100%）
- 全部 N01\~N05 通过（正常页面不误判）
- B01\~B03 通过（已知边界不误判）

## 三、第二层：Prompt 回归测试

### 3.1 测试目标

每次修改 Prompt 后，用一批固定的页面描述和任务上下文作为输入，验证云端返回的动作指令在格式和决策逻辑上没有退化。不依赖真机，只需要能访问 agnes-2.5-flash API。

### 3.2 测试方法

建立一个测试脚本（Python / Kotlin / 任意语言），循环发送 HTTP 请求，每个请求携带固定 Prompt 和固定输入，收集响应并校验。

### 3.3 测试用例

#### 组 A：输出格式检查（每次 Prompt 变更后必跑）

| 编号  | 检查项                  | 验收标准                                                                                            |
| --- | -------------------- | ----------------------------------------------------------------------------------------------- |
| F01 | 输出是纯 JSON            | 第一个字符为 `{` 或 `[`，最后一个为 `}` 或 `]`                                                                |
| F02 | 不包含 Markdown 标记      | 响应中不含 `或`json                                                                                   |
| F03 | 不包含解释文字              | JSON 前后无额外文本                                                                                    |
| F04 | 必填字段齐全               | action、target（如需要）、confidence 均存在                                                               |
| F05 | action 值合法           | tap / long\_press / swipe / type / key / wait / launch / scroll\_to / abort / task\_complete 之一 |
| F06 | target.method 合法     | id / label / coordinate 之一                                                                      |
| F07 | confidence 范围正确      | 0.0 \~ 1.0                                                                                      |
| F08 | keycode 合法           | BACK / HOME / ENTER / RECENT 之一                                                                 |
| F09 | direction 合法         | up / down / left / right 之一                                                                     |
| F10 | page\_fingerprint 回传 | 会改变页面的动作必须回传，且值与输入一致                                                                            |

**验收标准**：连续 20 次不同场景的调用，F01\~F10 全部通过（100% 格式正确率）。

#### 组 B：寻址方式检查

| 编号  | 场景                                | 验收标准                           |
| --- | --------------------------------- | ------------------------------ |
| B01 | source=accessibility + 目标有 id     | method="id"                    |
| B02 | source=accessibility + 目标无 id 有文字 | method="label"                 |
| B03 | source=accessibility 时            | 绝不使用 method="coordinate"       |
| B04 | source=screenshot 时               | 只使用 method="coordinate"        |
| B05 | source=screenshot 时               | coordinate 值为比例坐标（0\~1 范围的浮点数） |

**验收标准**：B01\~B05 各测 5 次，100% 符合期望。

#### 组 C：决策逻辑检查

| 编号  | 场景                                    | 期望动作                                 | 禁止出现的动作                         |
| --- | ------------------------------------- | ------------------------------------ | ------------------------------- |
| C01 | 倒计时广告页面（context\_hint 含 "⚠️ 疑似倒计时广告"） | wait                                 | tap                             |
| C02 | 页面同时有 "跳过 5s" 按钮和搜索框                  | wait                                 | tap（不能点跳过）                      |
| C03 | 支付确认页 + 任务含 "提交订单"                    | tap + needs\_user\_confirmation=true | needs\_user\_confirmation=false |
| C04 | 弹窗覆盖正常页面                              | tap（先关弹窗）                            | tap（直接点被遮挡的控件）                  |
| C05 | 上一步标记为 "❌ 未生效"                        | 更换策略（action 或 target 与上一步不同）         | 重复与上一步完全相同的动作                   |
| C06 | 上一步标记为 "⚠️ 已发送但未确认"                   | 重新评估并输出基于当前页面的决策                     | 直接跳过当前步骤进入下一步                   |
| C07 | 上一步标记为 "✅ 已确认成功"                      | 安全继续下一步                              | 无故重试或 wait                      |
| C08 | 搜索结果列表 + 用户要 "选评分最高的"                 | tap 第一个结果                            | tap 随机一个结果                      |
| C09 | 搜索框未获焦点 + 用户要输入关键词                    | tap 搜索框                              | type（输入前先要点输入框）                 |
| C10 | 购物车已满 + 用户要提交                         | tap "提交订单"                           | 继续加商品或做无关操作                     |

**验收标准**：C01\~C10 各测 5 次，通过率 > 95%（即 50 次调用中最多 2 次未通过）。

#### 组 D：动作合并检查

| 编号  | 场景                  | 验收标准                    |
| --- | ------------------- | ----------------------- |
| D01 | 搜索框已获焦点 + 需要输入并搜索   | 返回 JSON 数组 \[type, tap] |
| D02 | 弹窗遮挡 + 关闭弹窗后可点目标    | 返回 JSON 数组 \[tap, tap]  |
| D03 | 需要短等待后点击（≤ 2000ms）  | 返回 JSON 数组 \[wait, tap] |
| D04 | source=screenshot 时 | 绝不返回 JSON 数组            |
| D05 | 第一个动作会跳转到新页面        | 绝不返回 JSON 数组            |

**验收标准**：D01\~D05 各测 3 次，100% 符合期望。

#### 组 E：回归基准集（每次 Prompt 变更后必测）

准备 10 个固定的页面 JSON 文件和对应的任务上下文，覆盖以下场景：

| 编号  | 场景       | 来源 App            |
| --- | -------- | ----------------- |
| R01 | 正常点击决策   | 美团搜索结果页           |
| R02 | 弹窗处理     | 权限请求弹窗            |
| R03 | 列表滑动     | RecyclerView 列表页  |
| R04 | 文字输入     | 搜索框输入场景           |
| R05 | 倒计时广告    | 开屏广告页             |
| R06 | 误触后恢复    | 误触商品卡后跳转到非预期页面    |
| R07 | 截图路径决策   | source=screenshot |
| R08 | 不可逆操作    | 支付确认页             |
| R09 | 任务完成判断   | 下单成功页             |
| R10 | 异常 abort | 页面控件树为空           |

**测试方法**：每次 Prompt 修改后，用这 10 个固定输入调用，对比输出和基准输出的差异。基准输出在首次 Prompt 定稿时记录并人工确认正确。

**验收标准**：输出差异率 < 10%（即 10 个用例中最多 1 个可以有合理变化）。

### 3.4 Prompt 回归测试脚本示例

````python
# prompt_regression_test.py

import json
import requests

API_URL = "https://api.xxx.com/v1/chat/completions"
API_KEY = "your-api-key"

# 加载基准测试用例
with open("regression_cases.json") as f:
    cases = json.load(f)

def call_llm(system_prompt, user_prompt):
    response = requests.post(
        API_URL,
        headers={"Authorization": f"Bearer {API_KEY}"},
        json={
            "model": "agnes-2.5-flash",
            "temperature": 0.1,
            "max_tokens": 1024,
            "response_format": "json",
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt}
            ]
        },
        timeout=10
    )
    return response.json()["choices"][0]["message"]["content"]

def validate_format(output):
    """组 A：格式检查"""
    errors = []
    if not output.strip().startswith(("{", "[")):
        errors.append("F01: 首字符不是 { 或 [")
    if not output.strip().endswith(("}", "]")):
        errors.append("F01: 末字符不是 } 或 ]")
    if "```" in output:
        errors.append("F02: 包含 Markdown 标记")
    try:
        parsed = json.loads(output)
    except json.JSONDecodeError:
        errors.append("F04: 不是合法 JSON")
        return errors
    if "action" not in parsed:
        errors.append("F04: 缺少 action")
    # ... 更多格式检查
    return errors

def run_regression(system_prompt):
    results = {"passed": 0, "failed": 0, "details": []}
    for case in cases:
        output = call_llm(system_prompt, case["user_prompt"])
        errors = validate_format(output)
        # 与基准输出对比
        if errors:
            results["failed"] += 1
            results["details"].append({"case": case["id"], "errors": errors})
        else:
            results["passed"] += 1
    return results

if __name__ == "__main__":
    with open("system_prompt.txt") as f:
        system_prompt = f.read()
    results = run_regression(system_prompt)
    print(f"通过: {results['passed']}, 失败: {results['failed']}")
    for detail in results["details"]:
        print(f"  {detail['case']}: {', '.join(detail['errors'])}")
````

## 四、测试执行计划

| 触发条件        | 执行哪些测试            | 预计耗时    |
| ----------- | ----------------- | ------- |
| 每次提交代码      | 第一层（端侧决策引擎单元测试）   | < 30 秒  |
| 每次修改 Prompt | 第一层 + 第二层全量       | < 10 分钟 |
| CI/CD 流水线   | 第一层               | < 30 秒  |
| 周度回归        | 第一层 + 第二层（E 组基准集） | < 5 分钟  |

## 五、测试记录模板

```
测试日期：____
测试版本：____

第一层（单元测试）：
  □ 全部通过
  □ 失败用例：____

第二层（Prompt 回归）：
  组 A（格式检查）：__/10 项通过
  组 B（寻址方式）：__/5 项通过
  组 C（决策逻辑）：__/50 次通过
  组 D（动作合并）：__/15 次通过
  组 E（回归基准）：__/10 例一致

问题记录：
  ____
```

**文档版本**：v1.0
**创建日期**：2026-08-12

- [ ] 第一层和第二层共计约 50 个测试用例，全部在电脑上执行。第一层每次提交必跑，第二层每次 Prompt 修改必跑。

