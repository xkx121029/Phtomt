package com.phoneagent.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 换入语义测试：成功时新内容就位、旧内容清掉；失败时**旧内容必须原样还在**。
 *
 * 「失败时旧内容还在」是这个类存在的全部理由——把它退化成「先删再改名」，
 * `换入失败要回滚成原有内容` 会立刻红。
 */
class InstallSwapTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dir(parent: File, name: String, vararg files: String): File =
        File(parent, name).apply {
            mkdirs()
            files.forEach { File(this, it).writeText("$name/$it") }
        }

    @Test
    fun `换入成功后新内容就位且旧内容被清掉`() {
        val root = temp.newFolder("opt")
        val dest = dir(root, "jdk17", "bin")
        val staging = dir(root, "jdk17.staging", "bin-new")

        assertNull(InstallSwap.replace(staging, dest))

        assertTrue(File(dest, "bin-new").isFile)
        assertFalse("旧内容不能残留", File(dest, "bin").exists())
        assertFalse("staging 应该被移走", staging.exists())
        assertFalse("previous 应该被清掉", File(root, "jdk17.previous").exists())
    }

    @Test
    fun `目标不存在时直接就位`() {
        val root = temp.newFolder("opt")
        val dest = File(root, "gradle")
        val staging = dir(root, "gradle.staging", "bin")

        assertNull(InstallSwap.replace(staging, dest))
        assertTrue(File(dest, "bin").isFile)
    }

    @Test
    fun `换入失败要回滚成原有内容`() {
        val root = temp.newFolder("opt")
        val dest = dir(root, "jdk17", "bin")
        // staging 根本不存在：renameTo 必然失败，走到回滚分支
        val staging = File(root, "jdk17.staging")

        val reason = InstallSwap.replace(staging, dest)

        assertTrue("必须报错而不是静默成功", reason != null)
        assertTrue("错误要说明已回滚：$reason", reason!!.contains("回滚"))
        assertTrue("旧内容必须还在", File(dest, "bin").isFile)
    }

    @Test
    fun `上次残留的 previous 不会挡住这次换入`() {
        val root = temp.newFolder("opt")
        val dest = dir(root, "jdk17", "bin")
        dir(root, "jdk17.previous", "stale")
        val staging = dir(root, "jdk17.staging", "bin-new")

        assertNull(InstallSwap.replace(staging, dest))
        assertTrue(File(dest, "bin-new").isFile)
        assertFalse(File(root, "jdk17.previous").exists())
    }

    @Test
    fun `递归删除会清掉整棵树`() {
        val root = temp.newFolder("opt")
        val tree = File(root, "rootfs").apply { mkdirs() }
        File(tree, "bin").mkdirs()
        File(File(tree, "bin"), "sh").writeText("#!/bin/sh")
        File(tree, "lib").mkdirs()

        InstallSwap.deleteRecursive(tree)
        assertFalse(tree.exists())
    }

    @Test
    fun `删除不存在的路径不会抛异常`() {
        InstallSwap.deleteRecursive(File(temp.root, "nope"))
    }
}
