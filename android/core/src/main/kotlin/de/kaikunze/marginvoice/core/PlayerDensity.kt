package de.kaikunze.marginvoice.core

/**
 * What the player shows around the sentence being read. At large text the sentence keeps
 * the room: header and controls are pinned, so everything else gives way first.
 */
enum class PlayerDensity(val raw: String) {
    /** The sentence between the one before and the one after, under the book and chapter. */
    FULL("full"),

    /** Only the sentence being read, under a one-line chapter header. */
    FOCUSED("focused");

    val showsNeighbouringSentences: Boolean get() = this == FULL
    val showsBookTitle: Boolean get() = this == FULL

    companion object {
        /** From this text scale on the player is [FOCUSED]: Android's 150 %, iOS's first accessibility size. */
        const val FOCUSED_FROM = 1.5

        /** @param textScale 1.0 is the system's default text size (iOS: the scaled body size / 17; Android: the font scale). */
        fun of(textScale: Double): PlayerDensity = if (textScale >= FOCUSED_FROM) FOCUSED else FULL
    }
}
