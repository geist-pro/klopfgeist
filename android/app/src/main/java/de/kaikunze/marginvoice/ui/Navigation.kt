package de.kaikunze.marginvoice.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import java.util.UUID

sealed interface Route {
    data object Library : Route
    data class Player(val book: UUID) : Route
    data class Comments(val book: UUID) : Route
    data class CommentDetail(val book: UUID, val comment: UUID) : Route
    data object Settings : Route
    data object Commands : Route
    data object Log : Route
    data object Licences : Route

    /** For the saved instance state: the stack outlives a rotation, a fold or a resize. */
    fun encode(): String = when (this) {
        Library -> "library"
        is Player -> "player:$book"
        is Comments -> "comments:$book"
        is CommentDetail -> "comment:$book:$comment"
        Settings -> "settings"
        Commands -> "commands"
        Log -> "log"
        Licences -> "licences"
    }

    companion object {
        fun decode(raw: String): Route? {
            val parts = raw.split(":")
            fun id(i: Int) = parts.getOrNull(i)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            return when (parts[0]) {
                "library" -> Library
                "player" -> id(1)?.let(::Player)
                "comments" -> id(1)?.let(::Comments)
                "comment" -> id(1)?.let { b -> id(2)?.let { c -> CommentDetail(b, c) } }
                "settings" -> Settings
                "commands" -> Commands
                "log" -> Log
                "licences" -> Licences
                else -> null
            }
        }
    }
}

/** The back stack (iOS: the navigation stack and split view). Always starts with the library. */
class Navigator(initial: List<Route> = listOf(Route.Library)) {
    val stack = mutableStateListOf<Route>().apply {
        add(Route.Library)
        addAll(initial.dropWhile { it == Route.Library })
    }
    val top: Route get() = stack.last()
    fun push(route: Route) { stack.add(route) }
    fun pop(): Boolean = if (stack.size > 1) { stack.removeAt(stack.size - 1); true } else false
    fun popToLibrary() { while (stack.size > 1) stack.removeAt(stack.size - 1) }

    /** A comment chosen in a list shown beside its detail: replaces the one shown, so back does not walk through every comment looked at. */
    fun select(detail: Route.CommentDetail) {
        if (top is Route.CommentDetail) stack[stack.size - 1] = detail else push(detail)
    }

    /** A top-level destination (the navigation rail): from the library, with the book's player under its comments. */
    fun goTo(destination: Route) {
        popToLibrary()
        when (destination) {
            Route.Library -> {}
            is Route.Comments -> { push(Route.Player(destination.book)); push(destination) }
            else -> push(destination)
        }
    }

    companion object {
        val Saver: Saver<Navigator, Any> = listSaver(
            save = { nav -> nav.stack.map { it.encode() } },
            restore = { saved -> Navigator(saved.mapNotNull(Route::decode)) },
        )
    }
}
