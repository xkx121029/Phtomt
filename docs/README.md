# 文档索引

`docs/` 是本仓库的**权威文档源**；[Wiki](https://github.com/xkx121029/Phtomt/wiki) 是对外门户，内容从这些文件提炼。改动先落这里，再同步 Wiki。

## 发版与工程

| 文档 | 内容 |
|---|---|
| [release-pipeline.md](release-pipeline.md) | 谁触发什么包、两道硬断言、GitHub artifact、Gitee 补挂、排查 |
| [build-modules.md](build-modules.md) | 可选构建模块的开关与任务（android / vue / git / github），默认不参与构建图 |
| [project-structure.md](project-structure.md) | 14 个 Gradle 模块与依赖关系、目录与包职责 |
| [nightly-optimization-2026-11-06.md](nightly-optimization-2026-11-06.md) | nightly 线优化记录 |

## 契约与设计

| 文档 | 内容 |
|---|---|
| [assistant-role-contract.md](assistant-role-contract.md) | 助理角色边界 |
| [task-notice-contract.md](task-notice-contract.md) | 任务降级提示的出口与文案规则 |
| [virtual-display-design.md](virtual-display-design.md) | 虚拟副屏方案与限制 |

## UI

| 文档 | 内容 |
|---|---|
| [brand-vi.md](brand-vi.md) | 品牌与视觉识别 |
| [ui-refresh-plan.md](ui-refresh-plan.md) | 界面改版计划 |
| [ui-theme-handoff.md](ui-theme-handoff.md) | 主题交接说明 |

## 评测与其他

| 文档 | 内容 |
|---|---|
| [eta-ai-tools-and-prompts-comparison.md](eta-ai-tools-and-prompts-comparison.md) | 工具与提示词对比 |
| [eta-code-level.md](eta-code-level.md) | 代码级评测 |
| [eta-implementation-review.md](eta-implementation-review.md) | 实现评审 |
| [作品说明-评委版.md](作品说明-评委版.md) | 对外作品说明 |

## 重构记录

| 文档 | 内容 |
|---|---|
| [refactor/optimization-backlog-2026-11-12.md](refactor/optimization-backlog-2026-11-12.md) | 优化/重构清单（逐条现状与理由） |
| [refactor/optimization-executed-2026-11-12.md](refactor/optimization-executed-2026-11-12.md) | 上述清单 1–7 步的执行结果与验证方式 |
| [refactor/phase-b-step-runner.md](refactor/phase-b-step-runner.md) | 主循环拆成观察/决策/执行三段的契约与迁移理由 |

## 训练器接口

| 文档 | 内容 |
|---|---|
| [hpa-trainer/任务文档v2-格式与接口.md](hpa-trainer/任务文档v2-格式与接口.md) | 训练器 ↔ 本仓库的唯一接口约定（文档结构与 HTTP 面） |
| [hpa-trainer/PROBES.md](hpa-trainer/PROBES.md) | 线上探针记录 |
| [hpa-trainer/训练问题修复报告-2026-11-03.md](hpa-trainer/训练问题修复报告-2026-11-03.md) | 训练侧改动清单与部署记录 |

## 历史评审归档

| 文档 | 内容 |
|---|---|
| [reviews/代码审查与优化建议-2026-10-28.md](reviews/代码审查与优化建议-2026-10-28.md) | 全仓审查 |
| [reviews/性能优化与重构报告-2026-10-27.md](reviews/性能优化与重构报告-2026-10-27.md) | 性能专项 |
| [reviews/架构统一性审计报告.md](reviews/架构统一性审计报告.md) | 架构一致性审计 |
