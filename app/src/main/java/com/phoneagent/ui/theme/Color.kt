package com.phoneagent.ui.theme

import androidx.compose.ui.graphics.Color

// 品牌色：深海军蓝 + 电光蓝紫，取自应用图标的主色调
val BrandNavy = Color(0xFF0B0F19)
val BrandNavyLight = Color(0xFF2A3250)

// 主色（蓝紫）
val Accent = Color(0xFF6C7CFF)
val AccentContainer = Color(0xFFE0E4FF)
val OnAccentContainer = Color(0xFF1A2150)

// 辅助色（青）
val Cyan = Color(0xFF2DD4BF)
val CyanContainer = Color(0xFFC9F7F0)

// 中性色
val SurfaceLight = Color(0xFFF8F9FC)
val SurfaceDark = Color(0xFF0B0F19)
val OnSurfaceLight = Color(0xFF1A1D29)
val OnSurfaceDark = Color(0xFFE6E8F0)

// 功能色
val Success = Color(0xFF2E9E6B)
val Warning = Color(0xFFE8A33D)
val Error = Color(0xFFE5484D)

// 图标点缀（用于品牌区）
val IconIris = Color(0xFF8B5CF6)
val IconMint = Color(0xFF3DDC97)

// ========== 语义状态色令牌（深/浅主题通用，取代各页面重复定义） ==========
/** 成功态色板：主色 + 容器底 + 容器前景 */
val SuccessContainer = Color(0xFFE6F6EE)
val OnSuccessContainer = Color(0xFF14532D)
/** 警告态色板 */
val WarningContainer = Color(0xFFFFF3DD)
val OnWarningContainer = Color(0xFF5C3A00)
/** 错误态色板（沿用 Material 语义） */
val ErrorContainer = Color(0xFFFFE3E0)
val OnErrorContainer = Color(0xFF8C1D18)

// ========== 记忆图谱配色（与品牌色系对齐） ==========
/** 图谱中心根节点 */
val MemoryRoot = Accent
/** 异常经验分类（暖橙，接近 Warning 色系） */
val MemoryAnomaly = Color(0xFFF07B3E)
val MemoryAnomalySoft = Color(0xFFF7B48C)
/** 用户画像分类（鸢尾紫，对齐 tertiary） */
val MemoryProfile = IconIris
val MemoryProfileSoft = Color(0xFFC6AEF0)

// ========== 测试分组配色（对齐主题品牌色，保留分组辨识度） ==========
val TestReal = IconIris            // 真实场景
val TestFormat = Accent            // 格式合规
val TestTargeting = Cyan           // 目标定位
val TestDecision = Warning         // 决策
val TestMerge = Color(0xFFE05C8A)  // 链路聚合（玫红）
val TestRegression = Success       // 回归基准

// ========== 手机预览外壳配色（深/浅主题适配） ==========
/** 手机外壳主体色 */
val PhoneShellLight = Color(0xFFE8EAF0)
val PhoneShellDark = Color(0xFF10141B)
/** 手机外壳边框 */
val PhoneShellBorderLight = Color(0xFFC8CCD6)
val PhoneShellBorderDark = Color(0xFF2A2F3A)
/** 摄像头挖孔 */
val PhoneCameraHoleLight = Color(0xFFBFC3CF)
val PhoneCameraHoleDark = Color(0xFF3A4250)
/** 摄像头挖孔暗态 */
val PhoneCameraHoleIdleDark = Color(0xFF252A33)

// ========== 空状态配色 ==========
val EmptyStateIconLight = Color(0xFFB7BCCC)
val EmptyStateIconDark = Color(0xFF6A7385)
val EmptyStateTextLight = Color(0xFF8A94A6)
val EmptyStateTextDark = Color(0xFF6A7385)

// ========== 运行指示配色 ==========
val RunningIndicatorLight = Color(0xFF2E7D32)
val RunningIndicatorDark = Color(0xFF4CAF50)