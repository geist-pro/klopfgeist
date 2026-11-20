package de.kaikunze.marginvoice.core

import java.util.UUID

/** A comment or bookmark as the review lists and the scrubber see it (the app's `Comment` is one). */
interface ReviewEntry {
    val id: UUID
    val kind: CommentRecord.Kind
    val mediaTime: Double
    val endMediaTime: Double?
    val tag: ReviewTag?
}

/** The All / Comments / Bookmarks control above a list. */
enum class KindFilter { ALL, COMMENTS, BOOKMARKS }

/** Which comments a list shows: all, the untagged ones, or one tag. */
sealed interface TagFilter {
    data object All : TagFilter
    data object Untagged : TagFilter
    data class Tag(val tag: ReviewTag) : TagFilter

    fun includes(tag: ReviewTag?): Boolean = when (this) {
        All -> true
        Untagged -> tag == null
        is Tag -> tag == this.tag
    }

    fun label(language: VoiceCommand.Language): String = when (this) {
        All -> if (language == VoiceCommand.Language.GERMAN) "Alle Tags" else "All tags"
        Untagged -> if (language == VoiceCommand.Language.GERMAN) "Ohne Tag" else "Untagged"
        is Tag -> tag.displayName(language)
    }

    /** For saved UI state. */
    val key: String get() = when (this) {
        All -> "all"
        Untagged -> "untagged"
        is Tag -> tag.raw
    }

    companion object {
        val all: List<TagFilter> get() = listOf(All, Untagged) + ReviewTag.entries.map { Tag(it) }
        fun fromKey(key: String?): TagFilter = all.firstOrNull { it.key == key } ?: All
    }
}

/** A mark on the scrubber, as fractions of the book (0…1). */
data class ScrubberMarker(val kind: Kind, val start: Float, val end: Float?) {
    enum class Kind { COMMENT, BOOKMARK, PASSAGE }
}

/**
 * What the comment lists and the scrubber show. Pure: the playback time is a parameter.
 * The filter is the rule of the Apple app's `CommentsView` (kind first; a tag filter other
 * than All hides bookmarks, which carry no tag), kept identical.
 */
object ReviewListing {
    /** The entries the filters let through, in reading order. */
    fun <T : ReviewEntry> visible(entries: List<T>, kind: KindFilter, tags: TagFilter = TagFilter.All): List<T> =
        entries.sortedBy { it.mediaTime }.filter {
            when (kind) {
                KindFilter.ALL -> true
                KindFilter.COMMENTS -> it.kind == CommentRecord.Kind.COMMENT
                KindFilter.BOOKMARKS -> it.kind == CommentRecord.Kind.BOOKMARK
            }
        }.filter { if (it.kind == CommentRecord.Kind.BOOKMARK) tags == TagFilter.All else tags.includes(it.tag) }

    /**
     * The entry the playhead is at: the last one it has reached, in [sorted] (reading order).
     * Not the closest by distance: a comment still ahead has not been heard yet. Null before the first.
     */
    fun <T : ReviewEntry> current(sorted: List<T>, time: Double): T? = sorted.lastOrNull { it.mediaTime <= time }

    /** Not reached yet by the playhead. */
    fun isAhead(entry: ReviewEntry, time: Double): Boolean = entry.mediaTime > time

    /** The row a list keeps in view: the current entry, or before the first one the first row; null for an empty list. */
    fun focusIndex(sorted: List<ReviewEntry>, time: Double): Int? {
        if (sorted.isEmpty()) return null
        return maxOf(0, sorted.indexOfLast { it.mediaTime <= time })
    }

    /** Scrubber marks: bookmarks and passages first, comments last, so the comment dots are drawn on top. */
    fun markers(entries: List<ReviewEntry>, duration: Double): List<ScrubberMarker> {
        if (duration <= 0) return emptyList()
        fun f(t: Double) = (t / duration).coerceIn(0.0, 1.0).toFloat()
        val bookmarks = entries.filter { it.kind == CommentRecord.Kind.BOOKMARK }.map { b ->
            val end = b.endMediaTime
            if (end != null) ScrubberMarker(ScrubberMarker.Kind.PASSAGE, f(b.mediaTime), maxOf(f(b.mediaTime), f(end)))
            else ScrubberMarker(ScrubberMarker.Kind.BOOKMARK, f(b.mediaTime), null)
        }
        val comments = entries.filter { it.kind == CommentRecord.Kind.COMMENT }.map { ScrubberMarker(ScrubberMarker.Kind.COMMENT, f(it.mediaTime), null) }
        return bookmarks + comments
    }

    /** "4 comments · 1 bookmark", under a list's title. */
    fun summary(entries: List<ReviewEntry>): String {
        val comments = entries.count { it.kind == CommentRecord.Kind.COMMENT }
        val bookmarks = entries.size - comments
        fun n(count: Int, one: String) = "$count $one${if (count == 1) "" else "s"}"
        val parts = listOfNotNull(n(comments, "comment").takeIf { comments > 0 }, n(bookmarks, "bookmark").takeIf { bookmarks > 0 })
        return parts.joinToString(" · ").ifEmpty { "No comments yet" }
    }
}
