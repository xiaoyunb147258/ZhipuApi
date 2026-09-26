package dev.zhipu2api.model

/**
 * 智谱清言只有一个模型（GLM）。
 * 对外暴露几个 OpenAI 风格的别名，便于客户端识别。
 */
data class ZhipuModel(
    val id: String,
    val label: String,
    val supportThink: Boolean = true,
    val supportSearch: Boolean = true
)

object Models {

    val ALL: List<ZhipuModel> = listOf(
        ZhipuModel("glm", "GLM (智谱清言)", supportThink = true, supportSearch = true)
    )

    fun byId(id: String): ZhipuModel? = ALL.firstOrNull { it.id == id }

    /** 对外暴露的模型 id，含 -think / -search 变体 */
    fun openAiIds(): List<String> {
        val out = mutableListOf<String>()
        for (m in ALL) {
            out.add(m.id)
            if (m.supportThink) out.add("${m.id}-think")
            if (m.supportSearch) out.add("${m.id}-search")
            if (m.supportThink && m.supportSearch) out.add("${m.id}-think-search")
        }
        return out
    }
}
