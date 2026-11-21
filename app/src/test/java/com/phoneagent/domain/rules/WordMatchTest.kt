package com.phoneagent.domain.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 英文危险词匹配的唯一实现（[WordMatch]）：**左词边界 + 右邻不得是小写字母 + 大小写不敏感**。
 *
 * 为什么值得单独钉：这是安全判据（点击前的确认门、幂等保护、浏览器护栏）里**唯一**的英文匹配器，
 * 词表里那十几个词全靠它判「这个标签是不是危险」。两个方向都会出事：
 * - 少了左边界：`border`→`order`、`resend`→`send` 被当成危险词，用户每次点击都被弹门；
 * - 少了右边界：`sender`→`send`、`postcode`→`post`、`confirmed`→`confirm` 同理。
 * 而右邻是**大写**时必须继续拦：`DeleteAccountButton` / `#deleteComment` 是真危险的驼峰命名。
 *
 * 这里刻意不经 [DangerousTargetWords] 间接验证：那层还有语境豁免（导航词放行），
 * 豁免一改就会把匹配器本身的回归掩盖掉。
 */
class WordMatchTest {

    private fun hit(text: String?, vararg words: String) = WordMatch.hit(text, words.toList())

    @Test
    fun `左词边界_词中片段不算命中`() {
        // 真机来源：英文界面的 border / resend 被当成 order / send，普通按钮每次点都要确认
        assertNull("border 里的 order 不是独立词", hit("border", "order"))
        assertNull("resend 里的 send 不是独立词", hit("resend", "send"))
        assertNull("repayment 里的 payment 不是独立词", hit("repayment", "payment"))
    }

    @Test
    fun `右邻是小写字母_同词延伸不算命中`() {
        // 真机来源：Sender / Postcode / Confirmed 是纯导航标签，误拦等于每次点击加一道门
        assertNull("sender 是 send 的同词延伸", hit("sender", "send"))
        assertNull("postcode 是 post 的同词延伸", hit("postcode", "post"))
        assertNull("confirmed 是 confirm 的同词延伸", hit("confirmed", "confirm"))
    }

    @Test
    fun `右邻是大写字母_驼峰命名必须继续拦`() {
        // 右边界只认小写字母：大写开新词，所以这些真实控件名不能被放过
        assertEquals("delete", hit("DeleteAccountButton", "delete"))
        assertEquals("delete", hit("#deleteComment", "delete"))
        assertEquals("delete", hit("Row Delete", "delete"))
    }

    @Test
    fun `大小写不敏感_整词命中`() {
        assertEquals("delete", hit("DELETE", "delete"))
        assertEquals("delete", hit("Delete", "delete"))
        assertEquals("delete", hit("delete", "delete"))
        assertEquals("payment", hit("Payment method", "payment"))
    }

    @Test
    fun `词尾出现在字符串结束或非字母处都算命中`() {
        assertEquals("delete", hit("delete", "delete"))
        assertEquals("delete", hit("delete ", "delete"))
        assertEquals("delete", hit("delete!", "delete"))
        assertEquals("delete", hit("tap: delete", "delete"))
        assertEquals("delete", hit("删除 delete", "delete"))
    }

    @Test
    fun `命中回报词表里的原始词条_而不是正文片段`() {
        // 调用方拿它写日志与确认框说明；回报正文会让「命中哪个词」随标签措辞漂移
        assertEquals("Delete", hit("delete button", "Delete"))
        assertEquals("Place order", hit("PLACE ORDER now", "Place order"))
    }

    @Test
    fun `多词表按表序回报第一个命中的词`() {
        // 顺序有意义：词表把严格档排前面（浏览器护栏按命中词分档）
        assertEquals("send", hit("send and delete", "send", "delete"))
        assertEquals("delete", hit("send and delete", "delete", "send"))
    }

    @Test
    fun `空标签与空词表都不命中`() {
        assertNull(hit(null, "delete"))
        assertNull(hit("", "delete"))
        assertNull(hit("   ", "delete"))
        assertNull("没有词表时不能凭空命中", WordMatch.hit("delete", emptyList()))
    }

    @Test
    fun `不同词表的编译结果不互相串味`() {
        // 词表按内容缓存成预编译正则：缓存键错的实现会让后一次查询命中前一张表的词
        assertNull(hit("send", "order"))
        assertEquals("send", hit("send", "send"))
        assertNull(hit("send", "order"))
        assertEquals("order", hit("order", "order"))
        assertEquals("send", hit("send", "pay", "send"))
    }
}
