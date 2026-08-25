package com.fooddrugnote.api

import com.google.gson.annotations.SerializedName
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * 豆包（火山方舟ARK）多模态 API 接口
 * 文档：https://www.volcengine.com/docs/82379/1298454
 */
interface DoubaoService {

    @POST("api/v3/chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") auth: String,
        @Body body: RequestBody
    ): ChatCompletionResponse
}

/**
 * 豆包 API 响应实体
 */
data class ChatCompletionResponse(
    val id: String?,
    @SerializedName("object") val object_: String?,
    val created: Long?,
    val model: String?,
    val choices: List<Choice>?,
    val usage: Usage?
) {
    data class Choice(
        val index: Int?,
        val message: Message?,
        val finish_reason: String?
    ) {
        data class Message(
            val role: String?,
            val content: String?
        )
    }

    data class Usage(
        val prompt_tokens: Int?,
        val completion_tokens: Int?,
        val total_tokens: Int?
    )
}
