package com.gushenrui.commitgenie.model

/** 模型网关支持的请求与响应协议。 */
enum class ModelProtocol(private val displayName: String) {
    OPENAI("OpenAI /chat/completions"),
    ANTHROPIC("Anthropic /messages"),
    ;

    override fun toString(): String = displayName
}
