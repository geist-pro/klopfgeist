package de.kaikunze.marginvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ReviewListingTests {
    private data class Entry(
        override val kind: CommentRecord.Kind,
        override val mediaTime: Double,
        override val endMediaTime: Double? = null,
        override val tag: ReviewTag? = null,
        override val id: UUID = UUID.randomUUID(),
    ) : ReviewEntry

    private fun comment(at: Double, tag: ReviewTag? = null) = Entry(CommentRecord.Kind.COMMENT, at, tag = tag)
    private fun bookmark(at: Double, end: Double? = null) = Entry(CommentRecord.Kind.BOOKMARK, at, end)

    private val intro = comment(160.0, ReviewTag.STRENGTH)
    private val related = comment(555.0, ReviewTag.QUESTION)
    private val passage = bookmark(782.0, 850.0)
    private val design = comment(1100.0, ReviewTag.WEAKNESS)
    private val pending = comment(1290.0)
    // out of reading order on purpose: lists sort by time
    private val all = listOf(design, intro, pending, passage, related)

    // Filter (the same rule as CommentsView.swift: kind first, a tag filter hides bookmarks)

    @Test fun allIsInReadingOrder() {
        assertEquals(listOf(intro, related, passage, design, pending), ReviewListing.visible(all, KindFilter.ALL))
    }

    @Test fun kindFilterKeepsOneKind() {
        assertEquals(listOf(intro, related, design, pending), ReviewListing.visible(all, KindFilter.COMMENTS))
        assertEquals(listOf(passage), ReviewListing.visible(all, KindFilter.BOOKMARKS))
    }

    @Test fun aTagFilterHidesBookmarksSinceTheyHaveNoTag() {
        assertEquals(listOf(design), ReviewListing.visible(all, KindFilter.ALL, TagFilter.Tag(ReviewTag.WEAKNESS)))
        assertEquals(listOf(pending), ReviewListing.visible(all, KindFilter.ALL, TagFilter.Untagged))
        assertEquals(emptyList<Entry>(), ReviewListing.visible(all, KindFilter.BOOKMARKS, TagFilter.Untagged))
    }

    @Test fun tagFiltersRoundTripThroughTheirKey() {
        for (f in TagFilter.all) assertEquals(f, TagFilter.fromKey(f.key))
        assertEquals(TagFilter.All, TagFilter.fromKey("nonsense"))
    }

    // The entry at the playhead

    @Test fun theCurrentEntryIsTheLastOneThePlayheadHasReached() {
        val sorted = ReviewListing.visible(all, KindFilter.ALL)
        // 18:42, as in the design: the 18:20 comment, not the 21:30 one that is closer but still ahead
        assertEquals(design, ReviewListing.current(sorted, 1122.0))
        assertEquals(design, ReviewListing.current(sorted, 1100.0))
        assertEquals(passage, ReviewListing.current(sorted, 800.0))
        assertEquals(pending, ReviewListing.current(sorted, 99_999.0))
    }

    @Test fun nothingIsCurrentBeforeTheFirstEntry() {
        assertNull(ReviewListing.current(ReviewListing.visible(all, KindFilter.ALL), 10.0))
        assertNull(ReviewListing.current(emptyList<Entry>(), 10.0))
    }

    @Test fun entriesAheadAreTheOnesNotReachedYet() {
        val sorted = ReviewListing.visible(all, KindFilter.ALL)
        assertEquals(listOf(design, pending), sorted.filter { ReviewListing.isAhead(it, 1000.0) })
        assertTrue(sorted.none { ReviewListing.isAhead(it, 1290.0) })
    }

    @Test fun theListKeepsTheCurrentEntryInViewOrElseTheFirstAhead() {
        val sorted = ReviewListing.visible(all, KindFilter.ALL)
        assertEquals(3, ReviewListing.focusIndex(sorted, 1122.0))
        assertEquals(0, ReviewListing.focusIndex(sorted, 0.0))
        assertNull(ReviewListing.focusIndex(emptyList<Entry>(), 0.0))
    }

    // Scrubber marks

    @Test fun markersArePlacedAsFractionsOfTheBook() {
        val marks = ReviewListing.markers(listOf(comment(250.0), bookmark(500.0), bookmark(100.0, 300.0)), duration = 1000.0)
        // bookmarks are drawn first, so the comment dots stay on top
        assertEquals(
            listOf(
                ScrubberMarker(ScrubberMarker.Kind.BOOKMARK, 0.5f, null),
                ScrubberMarker(ScrubberMarker.Kind.PASSAGE, 0.1f, 0.3f),
                ScrubberMarker(ScrubberMarker.Kind.COMMENT, 0.25f, null),
            ),
            marks,
        )
    }

    @Test fun markersStayOnTheTrack() {
        val marks = ReviewListing.markers(listOf(comment(-5.0), bookmark(900.0, 1200.0), comment(2000.0)), duration = 1000.0)
        assertEquals(listOf(0.9f to 1f, 0f to null, 1f to null), marks.map { it.start to it.end })
    }

    @Test fun noMarkersWithoutADuration() {
        assertEquals(emptyList<ScrubberMarker>(), ReviewListing.markers(all, duration = 0.0))
    }

    // Summary under the list's title

    @Test fun summaryCountsCommentsAndBookmarks() {
        assertEquals("4 comments · 1 bookmark", ReviewListing.summary(all))
        assertEquals("1 comment", ReviewListing.summary(listOf(intro)))
        assertEquals("2 bookmarks", ReviewListing.summary(listOf(passage, bookmark(3.0))))
        assertEquals("No comments yet", ReviewListing.summary(emptyList<Entry>()))
    }
}
