package de.kaikunze.marginvoice.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.app.UiModeManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.kaikunze.marginvoice.R
import de.kaikunze.marginvoice.core.ReviewTag
import de.kaikunze.marginvoice.model.BookFont
import de.kaikunze.marginvoice.model.ColorTheme

// Design tokens from the iOS app (UI/Theme.swift): the same six palettes. Gruvbox is the
// default here; the dark ones suit the car at night.

/** A set of colors the whole app is painted with. */
@Immutable
data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val record: Color,
    val success: Color,
    val bookmark: Color,
    val warning: Color,
    /** Text on an accent-filled button. */
    val onAccent: Color,
    /**
     * Outlines, tracks and the off state of a switch: at least 3:1 against the background and
     * the surface. `surfaceBorder` stays for plain dividers, which need no contrast.
     */
    val control: Color,
    /** Whether system bars and Material's defaults are drawn for a dark page. */
    val isDark: Boolean = true,
) {
    /**
     * For the system's high-contrast setting: secondary text moves toward the primary, outlines
     * take the old secondary text colour and dividers the control colour. Page, surface and hues stay.
     */
    fun strongerContrast(): Palette =
        copy(textSecondary = lerp(textSecondary, textPrimary, 0.4f), control = textSecondary, surfaceBorder = control)

    companion object {
        val Standard = Palette(
            background = Color(0xFF0A0C10), surface = Color(0xFF111419), surfaceBorder = Color(0xFF23272E),
            textPrimary = Color(0xFFF5F6F7), textSecondary = Color(0xFF8A909A), accent = Color(0xFF2F80FF),
            record = Color(0xFFFF453A), success = Color(0xFF30D158), bookmark = Color(0xFFFFD60A),
            warning = Color(0xFFFF9500), onAccent = Color(0xFF0A0C10), control = Color(0xFF5C6370),
        )

        /** Gruvbox dark, hard contrast (morhetz/gruvbox): warm, easy on the eyes at night. */
        val Gruvbox = Palette(
            background = Color(0xFF1D2021), surface = Color(0xFF282828), surfaceBorder = Color(0xFF504945),
            textPrimary = Color(0xFFEBDBB2), textSecondary = Color(0xFFA89984), accent = Color(0xFF83A598),
            record = Color(0xFFFB4934), success = Color(0xFFB8BB26), bookmark = Color(0xFFFABD2F),
            warning = Color(0xFFFE8019), onAccent = Color(0xFF1D2021), control = Color(0xFF7C6F64),
        )

        /** Gruvbox dark, medium contrast: one step lighter than `Gruvbox`, yellow accent. */
        val GruvboxDark = Palette(
            background = Color(0xFF282828), surface = Color(0xFF32302F), surfaceBorder = Color(0xFF504945),
            textPrimary = Color(0xFFEBDBB2), textSecondary = Color(0xFFA89984), accent = Color(0xFFFABD2F),
            record = Color(0xFFFB6A57), success = Color(0xFFB8BB26), bookmark = Color(0xFFFE8019),
            warning = Color(0xFFE78A4E), onAccent = Color(0xFF1D2021), control = Color(0xFF928374),
        )

        /** Gruvbox light: the cream page. */
        val GruvboxLight = Palette(
            background = Color(0xFFFBF1C7), surface = Color(0xFFF2E5BC), surfaceBorder = Color(0xFFD5C4A1),
            textPrimary = Color(0xFF282828), textSecondary = Color(0xFF665C54), accent = Color(0xFF076678),
            record = Color(0xFF9D0006), success = Color(0xFF6A660C), bookmark = Color(0xFF8F5902),
            warning = Color(0xFFAF3A03), onAccent = Color(0xFFFBF1C7), control = Color(0xFF7C6F64), isDark = false,
        )

        /** GEIST (geist.pro): white page, navy, cyan rules. */
        val Geist = Palette(
            background = Color(0xFFFFFFFF), surface = Color(0xFFF2F6FB), surfaceBorder = Color(0xFFD6DEEA),
            textPrimary = Color(0xFF161616), textSecondary = Color(0xFF3A4A66), accent = Color(0xFF193F82),
            record = Color(0xFFB42318), success = Color(0xFF1F7A3A), bookmark = Color(0xFFA14A00),
            warning = Color(0xFFB54708), onAccent = Color.White, control = Color(0xFF7F8EA9), isDark = false,
        )

        /** GEIST dark: deep navy, cyan accent. */
        val GeistDark = Palette(
            background = Color(0xFF0B1220), surface = Color(0xFF121B30), surfaceBorder = Color(0xFF26335A),
            textPrimary = Color(0xFFF4F7FB), textSecondary = Color(0xFFAAB7D2), accent = Color(0xFF29B7EA),
            record = Color(0xFFFF7A6B), success = Color(0xFF7FD18B), bookmark = Color(0xFFF2A865),
            warning = Color(0xFFFFB454), onAccent = Color(0xFF161616), control = Color(0xFF5A6EA6),
        )

        fun of(theme: ColorTheme) = when (theme) {
            ColorTheme.GEIST_DARK -> GeistDark
            ColorTheme.GEIST -> Geist
            ColorTheme.GRUVBOX -> Gruvbox
            ColorTheme.GRUVBOX_DARK -> GruvboxDark
            ColorTheme.GRUVBOX_LIGHT -> GruvboxLight
            ColorTheme.STANDARD -> Standard
        }
    }
}

/** The colour of a review tag, from the palette: strength green, weakness red, question blue, minor grey, summary cream. */
fun Palette.tagTint(tag: ReviewTag): Color = when (tag) {
    ReviewTag.STRENGTH -> success
    ReviewTag.WEAKNESS -> record
    ReviewTag.QUESTION -> accent
    ReviewTag.MINOR -> textSecondary
    ReviewTag.SUMMARY -> textPrimary
}

object Space {
    val cardRadius = 18.dp
    val controlRadius = 12.dp
    val cardPadding = 18.dp
    val card = RoundedCornerShape(cardRadius)
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private fun family(res: Int) = FontFamily(listOf(400, 500, 600, 700).map { variable(res, it) })

/** The UI face, standing in for SF Pro. */
val Inter = family(R.font.inter)

/** The book's words: open faces closest to Apple's (New York, Charter, Iowan Old Style). */
fun bookFamily(font: BookFont): FontFamily = when (font) {
    BookFont.NEW_YORK -> family(R.font.source_serif)
    BookFont.CHARTER -> FontFamily(Font(R.font.charis_regular, FontWeight.Normal), Font(R.font.charis_bold, FontWeight.Bold))
    BookFont.IOWAN -> family(R.font.literata)
    BookFont.SANS -> Inter
}

/** iOS text styles at their default (Large) Dynamic Type sizes; they scale with the font scale. */
object TextStyles {
    private fun style(size: Int, weight: FontWeight = FontWeight.Normal, line: Double = 1.25) =
        TextStyle(fontFamily = Inter, fontSize = size.sp, fontWeight = weight, lineHeight = (size * line).sp)

    val largeTitle = style(34, FontWeight.Bold)
    val title = style(28)
    val title2 = style(22)
    val title3 = style(20)
    val headline = style(17, FontWeight.SemiBold)
    val body = style(17)
    val callout = style(16)
    val subheadline = style(15)
    val footnote = style(13)
    val caption = style(12)
    val caption2 = style(11)
}

/** Text that ends in "…" by design (titles, quotes): exempt from the clipped-text layout check. */
fun androidx.compose.ui.Modifier.truncates() = this.then(androidx.compose.ui.Modifier.testTag("truncates"))

val LocalPalette = staticCompositionLocalOf { Palette.Gruvbox }
val LocalBookFont = staticCompositionLocalOf { BookFont.NEW_YORK }

/** The current palette (`Theme.accent` on iOS is `Theme.palette.accent` here). */
object Theme {
    val palette: Palette @Composable get() = LocalPalette.current

    /** The book's words in the chosen face, in an iOS text style. */
    @Composable
    fun book(style: TextStyle, weight: FontWeight = FontWeight.Normal): TextStyle =
        style.copy(fontFamily = bookFamily(LocalBookFont.current), fontWeight = weight)
}

@Composable
fun MarginVoiceTheme(colors: ColorTheme, bookFont: BookFont, content: @Composable () -> Unit) {
    // Android 14's contrast setting (0 standard … 1 high); from medium on the stronger variant
    val context = LocalContext.current
    val highContrast = remember(context) {
        Build.VERSION.SDK_INT >= 34 && (context.getSystemService(UiModeManager::class.java)?.contrast ?: 0f) >= 0.5f
    }
    val p = Palette.of(colors).let { if (highContrast) it.strongerContrast() else it }
    // the same roles either way; the light or dark base only fills what is not named here
    val base = if (p.isDark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accent.copy(alpha = 0.22f), onPrimaryContainer = p.textPrimary,
        secondary = p.accent, onSecondary = p.onAccent, secondaryContainer = p.surfaceBorder, onSecondaryContainer = p.textPrimary,
        tertiary = p.bookmark, onTertiary = p.background, tertiaryContainer = p.bookmark.copy(alpha = 0.22f), onTertiaryContainer = p.textPrimary,
        background = p.background, onBackground = p.textPrimary,
        surface = p.background, onSurface = p.textPrimary, surfaceVariant = p.surface, onSurfaceVariant = p.textSecondary,
        surfaceContainerLowest = p.background, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surfaceBorder,
        surfaceBright = p.surfaceBorder, surfaceDim = p.background, surfaceTint = p.accent,
        inverseSurface = p.textPrimary, inverseOnSurface = p.background, inversePrimary = p.accent,
        error = p.record, onError = p.onAccent, errorContainer = p.record.copy(alpha = 0.22f), onErrorContainer = p.textPrimary,
        // outlines of text fields, outlined and segmented buttons are the only sign of the control
        outline = p.control, outlineVariant = p.surfaceBorder, scrim = Color.Black,
    )
    val typography = Typography(
        displayLarge = TextStyles.largeTitle, displayMedium = TextStyles.largeTitle, displaySmall = TextStyles.title,
        headlineLarge = TextStyles.title, headlineMedium = TextStyles.title, headlineSmall = TextStyles.title2,
        titleLarge = TextStyles.title2, titleMedium = TextStyles.headline, titleSmall = TextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyles.body, bodyMedium = TextStyles.subheadline, bodySmall = TextStyles.footnote,
        labelLarge = TextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), labelMedium = TextStyles.caption,
        labelSmall = TextStyles.caption2,
    )
    CompositionLocalProvider(LocalPalette provides p, LocalBookFont provides bookFont) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
