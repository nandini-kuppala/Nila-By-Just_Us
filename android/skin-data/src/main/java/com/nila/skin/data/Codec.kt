package com.nila.skin.data

import com.nila.skin.core.rules.Answer

/** Small text encodings for list-like columns, so the schema stays flat. */
object Codec {
    fun answers(map: Map<String, Answer>): String = map.entries.joinToString(",") { "${it.key}=${it.value.name}" }

    fun answers(text: String): Map<String, Answer> = text.split(',').mapNotNull { part ->
        val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        Answer.entries.firstOrNull { it.name == v }?.let { k to it }
    }.toMap()

    fun list(items: Collection<String>): String = items.joinToString("|")
    fun list(text: String?): List<String> = text?.split('|')?.filter { it.isNotBlank() } ?: emptyList()
}
