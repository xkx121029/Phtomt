package com.phoneagent.ui.onboarding

import com.phoneagent.ui.model.PermissionItem
import com.phoneagent.ui.model.PermissionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限页口径单测（UX-04 / UX-20 / UX-03）。
 *
 * 这些断言钉的是「用户看到的分母和标签」而不是实现细节：
 * - 进度分母只能是必做项数（1），不是「所有 non-optional 项」（6）；
 * - 非必做行的标签必须是「按需开启」，不能因为 `PermissionItem.optional == false`
 *   就把「推荐」组里的项标成必做；
 * - 「受限制的设置」只在真正可能灰置时才提示，且必做进度不受它影响。
 */
class OnboardingPermissionPolicyTest {

    private fun item(
        kind: PermissionKind,
        granted: Boolean,
        optional: Boolean = kind !in RequiredKinds,
    ) = PermissionItem(
        kind = kind,
        title = kind.name,
        description = kind.name,
        granted = granted,
        optional = optional,
    )

    /** MainViewModel 里那 10 项的口径：除使用情况访问权 / 所有文件 / 勿扰 / 麦克风外都是 optional=false */
    private fun allKinds(accessibilityGranted: Boolean) = listOf(
        item(PermissionKind.ACCESSIBILITY, accessibilityGranted, optional = false),
        item(PermissionKind.OVERLAY, false, optional = false),
        item(PermissionKind.WRITE_SETTINGS, false, optional = false),
        item(PermissionKind.AUTOSTART, false, optional = false),
        item(PermissionKind.NOTIFICATIONS, false, optional = false),
        item(PermissionKind.USAGE_ACCESS, false, optional = true),
        item(PermissionKind.INSTALL_PACKAGES, false, optional = false),
        item(PermissionKind.ALL_FILES, false, optional = true),
        item(PermissionKind.DND_ACCESS, false, optional = true),
        item(PermissionKind.MICROPHONE, false, optional = true),
    )

    // ==================== 分组 ====================

    @Test
    fun `必做组只有无障碍一项`() {
        assertEquals(setOf(PermissionKind.ACCESSIBILITY), RequiredKinds)
        assertEquals(1, RequiredKinds.size)
    }

    @Test
    fun `每个权限类型恰好出现在一个分组里`() {
        val flat = PermissionGroups.flatMap { it.kinds }
        assertEquals(PermissionKind.values().toSet(), flat.toSet())
        assertEquals("同一权限不能出现在两个组里，否则页面上会重复或漏项", flat.size, flat.toSet().size)
    }

    @Test
    fun `非必做组都标按需开启`() {
        assertEquals(listOf("必做", "推荐", "可选"), PermissionGroups.map { it.title })
        assertEquals(listOf(true, false, false), PermissionGroups.map { it.required })
    }

    // ==================== 进度分母（UX-04） ====================

    @Test
    fun `全未授权时分母是必做项数而不是全部非可选项`() {
        // 旧实现按「非 optional」算，这里会是 0 / 6；分组口径之后只能是 0 / 1
        val progress = requiredProgress(allKinds(accessibilityGranted = false))
        assertEquals(0, progress.granted)
        assertEquals(1, progress.total)
        assertFalse(progress.allGranted)
    }

    @Test
    fun `无障碍授权后进度变为就绪_其余项全部未开也不影响`() {
        val progress = requiredProgress(allKinds(accessibilityGranted = true))
        assertEquals(1, progress.granted)
        assertEquals(1, progress.total)
        assertTrue(progress.allGranted)
    }

    @Test
    fun `非必做项全部授权也不会把进度撑到就绪`() {
        val permissions = allKinds(accessibilityGranted = false).map { it.copy(granted = true) }
            .map { if (it.kind == PermissionKind.ACCESSIBILITY) it.copy(granted = false) else it }
        val progress = requiredProgress(permissions)
        assertEquals(0, progress.granted)
        assertEquals(1, progress.total)
        assertFalse(progress.allGranted)
    }

    @Test
    fun `空列表时不假装就绪`() {
        val progress = requiredProgress(emptyList())
        assertEquals(0, progress.total)
        assertFalse("首帧还没读到权限时不能报「必做已就绪」", progress.allGranted)
    }

    // ==================== 行标签（UX-20） ====================

    @Test
    fun `推荐组里的使用情况访问权不再标可选`() {
        // 该项在 VM 里 optional = true，但只要它躺在「推荐」组，标签就必须是「按需开启」
        val usageAccess = allKinds(accessibilityGranted = false)
            .first { it.kind == PermissionKind.USAGE_ACCESS }
        assertTrue(usageAccess.optional)
        assertEquals("按需开启", permissionPillText(granted = usageAccess.granted, required = false))
    }

    @Test
    fun `必做未授权是去授权_已授权一律完成`() {
        assertEquals("去授权", permissionPillText(granted = false, required = true))
        assertEquals("按需开启", permissionPillText(granted = false, required = false))
        assertEquals("完成", permissionPillText(granted = true, required = true))
        assertEquals("完成", permissionPillText(granted = true, required = false))
    }

    // ==================== 受限制的设置（UX-03） ====================

    @Test
    fun `安卓13侧载安装且未授权时应提示受限制设置`() {
        assertTrue(
            a11yMayBeRestricted(
                sdkInt = 33,
                installingPackage = null,
                providerInstalled = true,
                accessibilityGranted = false,
            ),
        )
        assertTrue(
            a11yMayBeRestricted(
                sdkInt = 34,
                installingPackage = "com.android.packageinstaller",
                providerInstalled = true,
                accessibilityGranted = false,
            ),
        )
    }

    @Test
    fun `已授权时不再提示`() {
        assertFalse(
            a11yMayBeRestricted(
                sdkInt = 33,
                installingPackage = null,
                providerInstalled = true,
                accessibilityGranted = true,
            ),
        )
    }

    @Test
    fun `安卓12及以下没有受限制设置`() {
        assertFalse(
            a11yMayBeRestricted(
                sdkInt = 32,
                installingPackage = null,
                providerInstalled = true,
                accessibilityGranted = false,
            ),
        )
    }

    @Test
    fun `组件未安装时问题是先装组件而不是受限设置`() {
        assertFalse(
            a11yMayBeRestricted(
                sdkInt = 33,
                installingPackage = null,
                providerInstalled = false,
                accessibilityGranted = false,
            ),
        )
    }

    @Test
    fun `应用商店安装的包不会被系统灰置`() {
        assertFalse(
            a11yMayBeRestricted(
                sdkInt = 33,
                installingPackage = "com.android.vending",
                providerInstalled = true,
                accessibilityGranted = false,
            ),
        )
    }
}
