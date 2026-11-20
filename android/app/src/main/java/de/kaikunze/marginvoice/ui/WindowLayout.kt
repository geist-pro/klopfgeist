package de.kaikunze.marginvoice.ui

/**
 * Material's window width classes, decided once in [AppRoot] from the window's width:
 * compact (phones) keeps the single-pane flow, medium (unfolded small foldables, small
 * tablets in portrait) shows the player beside its comments, expanded adds the navigation
 * rail and list-detail comments.
 */
enum class WidthClass {
    COMPACT, MEDIUM, EXPANDED;

    companion object {
        fun of(widthDp: Float): WidthClass = when {
            widthDp < 600f -> COMPACT
            widthDp < 840f -> MEDIUM
            else -> EXPANDED
        }
    }
}

/** The navigation rail's destinations (expanded windows). */
enum class RailDestination {
    LIBRARY, PLAYER, COMMENTS, SETTINGS;

    companion object {
        fun of(top: Route): RailDestination = when (top) {
            Route.Library -> LIBRARY
            is Route.Player -> PLAYER
            is Route.Comments, is Route.CommentDetail -> COMMENTS
            Route.Settings, Route.Commands, Route.Log, Route.Licences -> SETTINGS
        }
    }
}
