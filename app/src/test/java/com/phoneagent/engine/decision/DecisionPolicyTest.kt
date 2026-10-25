package com.phoneagent.engine.decision

import com.phoneagent.engine.decision.DecisionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 决策护栏策略真值表（阶段 B3） */
class DecisionPolicyTest {

    @Test
    fun 端侧连续未达阈值不强制云端复核() {
        assertFalse(DecisionPolicy.shouldForceCloudReview(0))
        assertFalse(DecisionPolicy.shouldForceCloudReview(4))
    }

    @Test
    fun 端侧连续达到阈值强制云端复核() {
        assertTrue(DecisionPolicy.shouldForceCloudReview(5))
        assertTrue(DecisionPolicy.shouldForceCloudReview(6))
        assertTrue(DecisionPolicy.shouldForceCloudReview(20))
    }

    @Test
    fun 决策异常未达上限不收尾() {
        assertFalse(DecisionPolicy.isDecisionFailureFatal(0))
        assertFalse(DecisionPolicy.isDecisionFailureFatal(4))
    }

    @Test
    fun 决策异常达到上限收尾() {
        assertTrue(DecisionPolicy.isDecisionFailureFatal(5))
        assertTrue(DecisionPolicy.isDecisionFailureFatal(9))
    }
}
