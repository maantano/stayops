package com.stayops.shared.web

import com.stayops.shared.error.ErrorCode
import com.stayops.shared.error.StayOpsException
import java.util.Base64
import java.util.UUID

/**
 * 목록 API 공통 cursor 페이지 (docs/v4.1/02 §6, 04 §10).
 * 정렬은 항상 (정렬 키, id)로 안정적이며 cursor는 마지막 항목의 (정렬 키, id)를 담은 불투명 문자열이다.
 */
data class PageResponse<T>(
    val items: List<T>,
    /** 다음 페이지 cursor. 마지막 페이지면 null. */
    val nextCursor: String?,
    val limit: Int,
)

object Paging {
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 100

    fun limit(requested: Int?): Int {
        val value = requested ?: DEFAULT_LIMIT
        if (value !in 1..MAX_LIMIT) throw StayOpsException(ErrorCode.VALIDATION_ERROR, "limit must be between 1 and $MAX_LIMIT")
        return value
    }

    fun encode(sortKey: String, id: UUID): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$sortKey|$id".toByteArray())

    /** @return (정렬 키, id). 형식이 틀리면 400. */
    fun decode(cursor: String): Pair<String, UUID> {
        val decoded = runCatching { String(Base64.getUrlDecoder().decode(cursor)) }.getOrNull()
        val sep = decoded?.lastIndexOf('|') ?: -1
        val id = if (sep > 0) runCatching { UUID.fromString(decoded!!.substring(sep + 1)) }.getOrNull() else null
        if (decoded == null || id == null) throw StayOpsException(ErrorCode.VALIDATION_ERROR, "cursor is invalid")
        return decoded.substring(0, sep) to id
    }

    /**
     * 메모리에 올린 정렬된 목록을 keyset 방식으로 자른다 (객실처럼 property당 수가 적은 목록용, MVP).
     * [items]는 이미 (sortKey, id) 오름차순이어야 한다.
     */
    fun <T> slice(items: List<T>, cursor: String?, limit: Int, sortKey: (T) -> String, id: (T) -> UUID): PageResponse<T> {
        val after = cursor?.let(::decode)
        val remaining = if (after == null) items else items.dropWhile { compareKey(sortKey(it), id(it), after) <= 0 }
        val page = remaining.take(limit)
        val next = if (remaining.size > limit) page.last().let { encode(sortKey(it), id(it)) } else null
        return PageResponse(page, next, limit)
    }

    private fun compareKey(key: String, id: UUID, after: Pair<String, UUID>): Int {
        val byKey = key.compareTo(after.first)
        return if (byKey != 0) byKey else id.toString().compareTo(after.second.toString())
    }
}
