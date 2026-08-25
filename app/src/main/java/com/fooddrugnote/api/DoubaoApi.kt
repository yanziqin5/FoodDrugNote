package com.fooddrugnote.api

import android.graphics.Bitmap
import android.util.Base64
import com.fooddrugnote.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 豆包多模态 API 封装类
 * 负责：构造请求、图片转base64、解析响应
 *
 * 使用模型：doubao-seed-2-1-pro-260628（Seed2.1 Pro 旗舰多模态）
 * 备选省钱模型：doubao-seed-2-0-mini-260428
 */
class DoubaoApi(private val apiKey: String) {

    companion object {
        private const val BASE_URL = "https://ark.cn-beijing.volces.com/"
        // 旗舰精准版（推荐）：药盒小字识别强、医药知识全
        // 注意：模型名必须与你火山方舟控制台里“推理”页签的模型 ID 完全一致
        const val MODEL_PRO = "doubao-seed-2-1-pro-260628"
        // 轻量化省钱版：响应快、消耗低，清晰药盒够用
        const val MODEL_MINI = "doubao-seed-2-0-mini-260428"
    }

    // 使用的模型，可外部切换
    var modelId: String = MODEL_MINI

    private val service: DoubaoService by lazy {
        // 调试构建打印完整请求/响应；发布构建关闭，避免泄露 base64 图片与响应内容
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        retrofit.create(DoubaoService::class.java)
    }

    /**
     * 扫描药盒图片，返回忌口分析文本
     * 使用 suspend 函数，可随协程取消而中断，避免 Activity 销毁后仍在浪费流量/配额。
     * @param bitmap 药盒图片（已压缩）
     * @return AI 分析结果文本
     */
    suspend fun scanDrugImage(bitmap: Bitmap): String {
        val base64Img = bitmapToBase64(bitmap)
        val bodyJson = buildRequestBodyJson(base64Img)

        val body: RequestBody = bodyJson.toRequestBody("application/json".toMediaType())
        val auth = "Bearer $apiKey"

        return try {
            val resp = service.chatCompletions(auth, body)
            resp.choices?.firstOrNull()?.message?.content
                ?: "未获取到分析结果，请重试"
        } catch (e: CancellationException) {
            // 协程被取消，原样抛出交由调用方处理
            throw e
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string() ?: "未知错误"
            // 尝试从 JSON 错误体中提取友好 message，避免把原始 JSON 直接暴露给用户
            val friendly = try {
                val json = org.json.JSONObject(errorBody)
                json.optString("message", errorBody).takeIf { it.isNotBlank() } ?: errorBody
            } catch (_: Exception) {
                errorBody
            }
            "请求失败(${e.code()})：$friendly"
        } catch (e: Exception) {
            "网络异常：${e.message}"
        }
    }

    /**
     * 按名称查询药/食相克（不拍照）：药-药、药-食、食-食三类相克识别
     * 复用现有 chatCompletions 通道，仅请求体改为纯文本（无图片）
     */
    suspend fun queryByName(name: String): String {
        val bodyJson = buildQueryRequestBodyJson(name)
        val body: RequestBody = bodyJson.toRequestBody("application/json".toMediaType())
        val auth = "Bearer $apiKey"
        return try {
            val resp = service.chatCompletions(auth, body)
            resp.choices?.firstOrNull()?.message?.content
                ?: "未获取到查询结果，请重试"
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string() ?: "未知错误"
            val friendly = try {
                val json = org.json.JSONObject(errorBody)
                json.optString("message", errorBody).takeIf { it.isNotBlank() } ?: errorBody
            } catch (_: Exception) {
                errorBody
            }
            "请求失败(${e.code()})：$friendly"
        } catch (e: Exception) {
            "网络异常：${e.message}"
        }
    }

    /**
     * 构造名称查询请求 JSON（纯文本，无图片）
     */
    private fun buildQueryRequestBodyJson(name: String): String {
        val systemPrompt = """
你是一个严谨的"药食相克"科普助手，严格遵守以下规则：
1. 先判断用户输入是【药品】还是【食物】（含饮品、水果）。都无法识别时，只返回一句话：未能识别该名称，请输入具体的药品名称或食物名称。
2. 基于公开权威资料，分三类列出不能与输入物搭配的对象（不适用的类别直接省略，不要空列）：
   - 药-药相克：与该药品不宜同服的其他药品
   - 药-食相克：该药品与日常食物/饮品/水果的禁忌
   - 食-食相克（仅当输入为食物时）：该食物不宜与哪些其他食物同食
3. 风险分两级，每条风险前必须带上对应标签：
   【高风险】（危及健康，如头孢+酒精、降压药+西柚、他汀+柚子）
   【低风险】（轻微降低药效或加重肠胃负担）
   请严格使用【高风险】和【低风险】标签标注每条风险，不要使用其他表述。
4. 每条风险后用括号简要说明原理。
5. 严禁输出任何诊断、用药剂量、治疗方案、替代药物建议，不扮演医生。
6. 回答末尾强制固定一行免责声明：⚠️ 本内容仅为公开科普资料整理，不构成专业医疗建议，服药务必遵从医师指导。
7. 纯文本排版，换行分段，不要使用 Markdown 表格。
        """.trimIndent()

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", name)
            })
        }

        return JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
            put("temperature", 0.3)
            put("max_tokens", 2048)
        }.toString()
    }

    /**
     * 构造请求 JSON
     * 多模态消息格式：[{role: "user", content: [{type:"text", text:"..."}, {type:"image_url", image_url:{url:"data:image/jpeg;base64,..."}}]}]
     */
    private fun buildRequestBodyJson(base64Img: String): String {
        val systemPrompt = """
你是一个严谨的"药食相克"科普助手，严格遵守以下规则：
1. 先识别图片中的药品名称、成分、适应症（若图片为食物/保健品则识别其名称）。
2. 基于公开权威药品说明书、药典、三甲医院科普资料，分三类列出该药物/食物相关的相克、禁忌、相互作用风险（不适用的类别直接省略，不要空列）：
   - 药-药相克：与该药品不宜同服的其他药品
   - 药-食相克：该药品与日常食物/饮品/水果的禁忌
   - 食-食相克（仅当图片识别为食物/保健品时适用）：该食物不宜与哪些其他食物同食
3. 风险分两级，每条风险前必须带上对应标签：
   【高风险】（危及健康，如头孢+酒精、降压药+西柚、他汀+柚子）
   【低风险】（轻微降低药效或加重肠胃负担）
   请严格使用【高风险】和【低风险】标签标注每条风险，不要使用其他表述（如"中风险"等）。
4. 每条风险后用括号简要说明原理（如：西柚抑制肝药酶，导致血药浓度翻倍）。
5. 严禁输出任何诊断、用药剂量、治疗方案、替代药物建议，不扮演医生。
6. 回答末尾强制固定一行免责声明：⚠️ 本内容仅为公开科普资料整理，不构成专业医疗建议，服药务必遵从医师指导。
7. 如果图片模糊、反光、无法识别出具体名称，直接返回一句话：未识别到清晰的文字，请正对物品、在光线充足处重新拍摄。
8. 纯文本排版，换行分段，不要使用 Markdown 表格。
        """.trimIndent()

        // 系统指令放在独立的 system 角色，用户消息仅携带图片，避免指令被混入 user 内容降低遵循度
        val userContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64Img")
                })
            })
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })
        }

        return JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
            put("temperature", 0.3)
            put("max_tokens", 2048)
        }.toString()
    }

    /**
     * Bitmap 转 JPEG base64 字符串
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream().use { baos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            baos.toByteArray()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
