package de.kaikunze.marginvoice.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WindowLayoutTests {
    @Test fun widthClassesFollowMaterialBreakpoints() {
        assertEquals(WidthClass.COMPACT, WidthClass.of(277f))
        assertEquals(WidthClass.COMPACT, WidthClass.of(599.9f))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(600f))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(839.9f))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(840f))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(1366f))
    }

    @Test fun routesSurviveSavingAsStrings() {
        val book = UUID.randomUUID()
        val comment = UUID.randomUUID()
        val stack = listOf(Route.Library, Route.Player(book), Route.Comments(book), Route.CommentDetail(book, comment), Route.Settings, Route.Commands, Route.Log, Route.Licences)
        assertEquals(stack, stack.map { Route.decode(it.encode()) })
        assertEquals(null, Route.decode("player:not-a-uuid"))
        assertEquals(null, Route.decode("nonsense"))
    }

    @Test fun railDestinationFollowsTheTopOfTheStack() {
        val book = UUID.randomUUID()
        assertEquals(RailDestination.LIBRARY, RailDestination.of(Route.Library))
        assertEquals(RailDestination.PLAYER, RailDestination.of(Route.Player(book)))
        assertEquals(RailDestination.COMMENTS, RailDestination.of(Route.Comments(book)))
        assertEquals(RailDestination.COMMENTS, RailDestination.of(Route.CommentDetail(book, UUID.randomUUID())))
        assertEquals(RailDestination.SETTINGS, RailDestination.of(Route.Licences))
    }

    @Test fun inAnExpandedWindowSelectingAnotherCommentReplacesTheSelection() {
        val book = UUID.randomUUID()
        val (a, b) = UUID.randomUUID() to UUID.randomUUID()
        val nav = Navigator()
        nav.push(Route.Player(book))
        nav.push(Route.Comments(book))
        nav.select(Route.CommentDetail(book, a))
        nav.select(Route.CommentDetail(book, b))
        assertEquals(listOf(Route.Library, Route.Player(book), Route.Comments(book), Route.CommentDetail(book, b)), nav.stack.toList())
        // back clears the selection, then leaves the comments
        assertTrue(nav.pop())
        assertEquals(Route.Comments(book), nav.top)
    }

    @Test fun aTopLevelDestinationStartsFromTheLibrary() {
        val book = UUID.randomUUID()
        val nav = Navigator()
        nav.push(Route.Player(book))
        nav.push(Route.Comments(book))
        nav.goTo(Route.Settings)
        assertEquals(listOf(Route.Library, Route.Settings), nav.stack.toList())
        nav.goTo(Route.Comments(book))
        // the player stays under the comments, so back returns to it
        assertEquals(listOf(Route.Library, Route.Player(book), Route.Comments(book)), nav.stack.toList())
        nav.goTo(Route.Library)
        assertFalse(nav.pop())
    }
}
