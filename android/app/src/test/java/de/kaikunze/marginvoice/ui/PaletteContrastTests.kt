package de.kaikunze.marginvoice.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import de.kaikunze.marginvoice.model.ColorTheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.2 contrast of the colour pairs the app draws, in every palette: text needs
 * 4.5:1, an outline or track that is the only sign of a control 3:1.
 */
class PaletteContrastTests {
    private fun contrast(a: Color, b: Color): Float {
        val (la, lb) = a.luminance() to b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun each(check: (ColorTheme, Palette) -> Unit) = ColorTheme.entries.forEach { check(it, Palette.of(it)) }

    private fun atLeast(min: Float, a: Color, b: Color, what: String) {
        val ratio = contrast(a, b)
        assertTrue("$what is $ratio:1, needs $min:1", ratio >= min)
    }

    @Test fun textReadsOnThePageAndTheSurface() = each { theme, p ->
        for ((name, text) in listOf("primary" to p.textPrimary, "secondary" to p.textSecondary, "accent" to p.accent)) {
            atLeast(4.5f, text, p.background, "$theme $name on the page")
            atLeast(4.5f, text, p.surface, "$theme $name on the surface")
        }
    }

    /** "Recording", "Comment saved", the tag names, "Not transcribed". */
    @Test fun statusColoursReadAsText() = each { theme, p ->
        for ((name, text) in listOf("record" to p.record, "success" to p.success, "bookmark" to p.bookmark, "warning" to p.warning)) {
            atLeast(4.5f, text, p.background, "$theme $name on the page")
        }
    }

    /** Play and Comment: the label or glyph on the filled button. */
    @Test fun labelsReadOnTheFilledButtons() = each { theme, p ->
        atLeast(4.5f, p.onAccent, p.accent, "$theme on the accent")
        atLeast(4.5f, p.onAccent, p.record, "$theme on the record colour")
    }

    /** Outlines, tracks and the off state of a switch. */
    @Test fun theControlColourShowsOnThePageAndTheSurface() = each { theme, p ->
        atLeast(3f, p.control, p.background, "$theme on the page")
        atLeast(3f, p.control, p.surface, "$theme on the surface")
    }

    /** High contrast: secondary text and outlines get stronger, dividers become visible lines. */
    @Test fun theStrongerVariantRaisesSecondaryTextOutlinesAndDividers() = each { theme, p ->
        val strong = p.strongerContrast()
        assertTrue("$theme secondary text", contrast(strong.textSecondary, p.background) > contrast(p.textSecondary, p.background))
        assertTrue("$theme outlines", contrast(strong.control, p.background) > contrast(p.control, p.background))
        atLeast(3f, strong.surfaceBorder, p.background, "$theme dividers on the page")
        atLeast(3f, strong.surfaceBorder, p.surface, "$theme dividers on the surface")
        // hue and layout stay: the page, the surface and the accent are the theme's
        assertTrue(strong.background == p.background && strong.surface == p.surface && strong.accent == p.accent)
    }

    /** The same six palettes as the Apple apps, light ones included. */
    @Test fun everyAppleThemeExistsHere() {
        assertTrue(ColorTheme.entries.map { it.raw }.containsAll(listOf("geistDark", "geist", "gruvbox", "gruvboxDark", "gruvboxLight", "standard")))
    }
}
