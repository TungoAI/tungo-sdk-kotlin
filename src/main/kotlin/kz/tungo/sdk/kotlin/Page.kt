package kz.tungo.sdk.kotlin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kz.tungo.sdk.kotlin.errors.TungoPaginationException

/**
 * One page of a cursor-paginated list.
 *
 * @param T type of the items
 * @param R type of the whole response body (it may carry more than the items)
 */
public class Page<out T, out R> internal constructor(
    /** Items of this page. */
    public val data: List<T>,
    /** Cursor of the next page; null or empty on the last page. */
    public val nextCursor: String?,
    /** The response this page was decoded from: body, status, headers. */
    public val response: ApiResponse<R>,
    private val requestCursor: String?,
    private val fetch: suspend (cursor: String) -> Page<T, R>,
) {
    /** Every cursor requested on the way to this page, from the first page of the walk. */
    private var seenCursors: Set<String> = emptySet()

    /** The whole response body. */
    public val body: R get() = response.body

    /** True when another page can be requested. */
    public val hasNext: Boolean get() = !nextCursor.isNullOrEmpty()

    /**
     * Requests the next page with the same filters; returns null on the last page.
     *
     * @throws TungoPaginationException when the API returns a cursor that was already requested on
     *   the way to this page (the same cursor again, or a cycle such as A, B, A)
     */
    public suspend fun next(): Page<T, R>? {
        val cursor = nextCursor
        if (cursor.isNullOrEmpty()) return null
        if (cursor == requestCursor || cursor in seenCursors) {
            throw TungoPaginationException("The API returned the same cursor twice; stopping to avoid an endless loop")
        }
        val page = fetch(cursor)
        page.inherit(seenCursors + listOfNotNull(requestCursor) + cursor)
        return page
    }

    private fun inherit(cursors: Set<String>) {
        seenCursors = cursors
    }

    /** A cold flow of the items of this page and of every following page, fetched lazily. */
    public fun asFlow(): Flow<T> = flow {
        var page: Page<T, R>? = this@Page
        while (page != null) {
            for (item in page.data) emit(item)
            page = page.next()
        }
    }

    override fun toString(): String = "Page(size=${data.size}, hasNext=$hasNext)"
}
