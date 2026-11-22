package com.phoneagent.coding.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P1-2 的反面测试（Kotlin 侧）：工作区路径判据必须在解析符号链接之后比前缀。
 *
 * 对侧实现是 Node 的 `resolveWithin`（`agent-host/tools/fs-tools.js`），它用 realpath；
 * 这一侧用 `canonicalFile`。**两侧语义必须一致**：都是「先归一符号链接，再比真实前缀」。
 * Node 侧也有一条必须拒绝的用例（`packages/agent-core/test/security-fs-path.test.js`，
 * 用真的 Windows junction 逃逸），哪一侧改语义，两条测试都会指回这句话。
 *
 * 为什么测 `resolveWithin(root: File, rel)` 而不是带 Context 的那个重载：
 * `Workspace.resolve(context)` 走 SharedPreferences，JVM 单测里拿不到真 Context，
 * 测出来的是桩而不是判据。判据本身已经抽成不碰 Context 的纯函数。
 */
class DeviceWorkspacePathTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().apply { mkdirs() }

    @Test
    fun `普通相对路径解析到工作区内`() {
        val root = tempDir("haca-kotlin-ws")
        assertEquals(File(root.canonicalFile, "src/a.txt").path, DeviceWorkspace.resolveWithin(root, "src/a.txt").path)
    }

    @Test
    fun `点级越界必须拒绝`() {
        val root = tempDir("haca-kotlin-ws")
        val ex = runCatching { DeviceWorkspace.resolveWithin(root, "../outside.txt") }.exceptionOrNull()
        assertTrue(".. 必须被当成越界，实际：$ex", ex is IllegalArgumentException)
    }

    @Test
    fun `绝对路径不能当成工作区内路径`() {
        val root = tempDir("haca-kotlin-ws")
        val outside = tempDir("haca-kotlin-outside")
        // 绝对路径必须当场被拒，而不是拼出一条越界路径或非法路径再说。
        val absolute = File(outside, "x.txt").absolutePath
        val posix = runCatching { DeviceWorkspace.resolveWithin(root, absolute) }.exceptionOrNull()
        assertTrue("绝对路径必须被拒，实际：$posix", posix is IllegalArgumentException)
        val drive = runCatching { DeviceWorkspace.resolveWithin(root, "C:/Users/Public/x.txt") }.exceptionOrNull()
        assertTrue("盘符路径必须被拒，实际：$drive", drive is IllegalArgumentException)
    }

    @Test
    fun `目录链接指向工作区外时必须拒绝`() {
        val root = tempDir("haca-kotlin-ws")
        val outside = tempDir("haca-kotlin-outside-link")
        File(outside, "x.jpg").writeText("secret")
        val link = File(root, "photos")
        // Windows 上 mklink /J（junction）不需要管理员权限；其它平台用符号链接。
        val created = runCatching {
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
                ProcessBuilder("cmd", "/c", "mklink", "/J", link.absolutePath, outside.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0 && link.exists()
            } else {
                Files.createSymbolicLink(link.toPath(), outside.toPath())
                true
            }
        }.getOrDefault(false)
        assumeTrue("本机不允许创建目录链接，跳过（Node 侧用同样的 junction 覆盖同一语义）", created)

        // 这条是本次最实在的回归：getCanonicalPath 不解析 junction，旧实现会静默放行。
        val ex = runCatching { DeviceWorkspace.resolveWithin(root, "photos/x.jpg") }.exceptionOrNull()
        assertTrue("指向工作区外的链接必须当成越界，实际：$ex", ex is IllegalArgumentException)
    }

    @Test
    fun `指向工作区内的链接仍然可解析`() {
        val root = tempDir("haca-kotlin-ws")
        File(root, "src").mkdirs()
        File(root, "src/a.txt").writeText("inside")
        val link = File(root, "alias")
        val created = runCatching {
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
                ProcessBuilder("cmd", "/c", "mklink", "/J", link.absolutePath, File(root, "src").absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0 && link.exists()
            } else {
                Files.createSymbolicLink(link.toPath(), File(root, "src").toPath())
                true
            }
        }.getOrDefault(false)
        assumeTrue("本机不允许创建目录链接", created)

        // 判据是真实路径，不是「有链接就拒」：解析成功，且真实路径落在工作区内。
        val resolved = DeviceWorkspace.resolveWithin(root, "alias/a.txt")
        val real = resolved.toPath().toRealPath().toFile()
        assertTrue("链接指向工作区内就该放行，实际：$real", real.path.startsWith(root.toPath().toRealPath().toFile().path))
    }

    @Test
    fun `还不存在的新文件不会被判成越界`() {
        val root = tempDir("haca-kotlin-ws")
        // 写入路径的父目录还不存在也要能过：解析最近的存在祖先即可。
        val resolved = DeviceWorkspace.resolveWithin(root, "new/dir/file.txt")
        assertTrue(resolved.path.startsWith(root.toPath().toRealPath().toFile().path))
    }
}
