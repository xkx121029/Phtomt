# 致谢

本项目的界面形态、运行时组织与工程取舍，参考了下列项目的公开实践。

**以上所列项目均未被内嵌进本应用**（源码、二进制、构建产物都没有），这里只履行署名与许可证告知义务。
如果将来引入其中任何项目的代码，会按其许可证重新评估分发义务，并在本文件补全相应条款与声明。

许可证名称均按各仓库根目录的 `LICENSE` 文件核对，不作推测。

---

## 1. Eta

- 仓库：<https://github.com/Mangi-11/Eta>
- 许可证：**PolyForm Noncommercial License 1.0.0**
  （<https://polyformproject.org/licenses/noncommercial/1.0.0>）
- 版权声明（许可证要求随附）：`Required Notice: Copyright © 2026 蛮吉 (Mangi-11)`
- 启发了我们什么：浮层胶囊、完成卡片与系统工具调用链路的参考实现。
  本项目的浮层操作条、任务完成卡，以及「模型调用系统能力」这条链路的组织方式，都对着它做过取舍。
- 说明：PolyForm Noncommercial 是**非商业许可**，不属于 OSI 认可的开源许可证。
  本项目只参考实现思路，未复制其代码，也未分发其软件副本。

## 2. pi

- 仓库：<https://github.com/earendil-works/pi>
- 许可证：**MIT License**
- 版权声明：`Copyright (c) 2025 Mario Zechner`
- 启发了我们什么：系统级集成与可扩展性的参考。
  它把「统一模型接口 + agent 循环 + 工具层」拆开再组合的做法，影响了本项目执行器与工具注册的边界划分。

## 3. deepseek-harness

- 仓库：<https://github.com/deepseek-ai/deepseek-harness>
- 许可证：**MIT License**
- 版权声明：`Copyright (c) 2026 DeepSeek`
- 启发了我们什么：Coding 执行器与 agent 循环的参考。
  本项目编码页的工具调用序列、失败重试与产物落盘顺序，参照了它的循环结构。

## 4. dsh-Go

- 仓库：<https://github.com/ccch1mneyyy/dsh-Go>
- 许可证：**GNU Affero General Public License v3.0（AGPL-3.0）**
- 版权声明：仓库 `LICENSE` 未另附单独版权行；版权归作者 ccch1mneyyy 所有。
- 启发了我们什么：手机端 Coding 实现的参考。
  在手机上跑编码任务时，它对「终端交互、文件预览、长任务回显」的取舍，为本项目的界面决策提供了参照。

---

## 其他参考

以下项目同样只作思路参考，未内嵌代码：

| 项目 | 许可证 | 参考点 |
|---|---|---|
| [Coda](https://github.com/happy-everyday-everyweek/coda) | AGPL-3.0 | 安卓端 Agent 的工程组织与运行时方案 |
| [MobileGym](https://github.com/Purewhiter/mobilegym) | Apache License 2.0 | 移动 GUI Agent 的任务设计与可验证思路 |
| [Aether 扶摇](https://github.com/Zhou-Shilin/Aether) | GPL-3.0 | 「进行中」状态的回显手法：过程弱化、结果成卡、思考中微光扫过文字 |

---

## 实际内嵌的第三方组件

与上面的「参考项目」不同，下面两件代码确实随本应用分发，义务按其许可证履行：

- **Shizuku API** — <https://github.com/RikkaApps/Shizuku-API> — MIT License
  - 版权：Copyright © 2021 RikkaW
  - 完整许可证文本随源码分发，见 `third_party/shizuku` 下的 `LICENSE`。
- **Shizuku**（server / starter）— <https://github.com/RikkaApps/Shizuku> — Apache License 2.0
  - 随「服务组件」一并分发，免装官方 App 直接拉起执行服务；保留其版权声明与 NOTICE。
