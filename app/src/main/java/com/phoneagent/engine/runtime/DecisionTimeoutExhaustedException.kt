package com.phoneagent.engine.runtime

/**
 * 云端决策连续多次被单步看门狗掐断（典型是网络不可达 / 服务端长时间不响应）。
 *
 * 与普通网络异常的区别：普通异常会在 [com.phoneagent.core.ai.AiClient] 内部重试后抛出，
 * 由主循环按「决策链路异常」计数收尾；而看门狗超时会降级成 `wait` 意图继续空转，
 * 在没有步数上限（maxSteps=0）的配置下会无限重试。用这个异常把它接入同一套收尾逻辑，
 * 避免用户看到「一直在等」却永远等不到结果。
 */
internal class DecisionTimeoutExhaustedException(val attempts: Int) :
    RuntimeException("云端连续 $attempts 次决策超时")
