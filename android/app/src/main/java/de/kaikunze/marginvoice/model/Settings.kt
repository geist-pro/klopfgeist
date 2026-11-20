package de.kaikunze.marginvoice.model

import androidx.compose.runtime.mutableStateOf
import de.kaikunze.marginvoice.core.VoiceCommand
import java.util.Locale
import kotlin.reflect.KProperty

/** User preferences. Keys match the iOS app's UserDefaults keys. */
class Settings(private val store: KeyValueStore, systemLanguage: String = Locale.getDefault().language) {
    var voiceCommandsEnabled: Boolean by Flag("voiceCommandsEnabled", true)
    /** Language the commands are spoken in (also the language of announcements). */
    var commandLanguage: VoiceCommand.Language by Choice(
        "commandLanguage", if (systemLanguage.startsWith("de")) VoiceCommand.Language.GERMAN else VoiceCommand.Language.ENGLISH,
        VoiceCommand.Language.entries, { it.code },
    )
    /** Language comments are spoken in (transcription locale). */
    var commentLanguage: CommentLanguage by Choice("commentLanguage", CommentLanguage.SAME_AS_BOOK, CommentLanguage.entries, { it.raw })
    /** Seconds subtracted from the "stop" time to anchor what you just heard. */
    var reactionOffset: Double by Number("reactionOffset", 1.5)
    /** After a comment, play on from this many seconds before "stop" was said. */
    var resumeRewind: Double by Number("resumeRewind", 2.0)
    /** End a comment after `silenceTimeout` of silence; otherwise only "continue" (or a tap) ends it. */
    var endCommentOnSilence: Boolean by Flag("endCommentOnSilence", false)
    /** Silence that ends a comment (when `endCommentOnSilence` is on). */
    var silenceTimeout: Double by Number("silenceTimeout", 6.0)
    /** Minutes a paused book keeps listening for commands; 0 = until the book closes. */
    var stopListeningAfter: Double by Number("stopListeningAfter", 5.0)
    var defaultRate: Double by Number("defaultRate", 1.0)
    /** Use the car or headset microphone (Bluetooth SCO). Lowers book audio quality. */
    var useHeadsetMicrophone: Boolean by Flag("useHeadsetMicrophone", false)
    var keepScreenAwake: Boolean by Flag("keepScreenAwake", true)
    var colorTheme: ColorTheme by Choice("colorTheme", ColorTheme.GRUVBOX, ColorTheme.entries, { it.raw })
    var bookFont: BookFont by Choice("bookFont", BookFont.NEW_YORK, BookFont.entries, { it.raw })
    var showDebugLog: Boolean by Flag("showDebugLog", false)
    /** When a book ends, open the next unfinished one in the library and keep playing. */
    var continueWithNextBook: Boolean by Flag("continueWithNextBook", true)
    /** Android only: listen through the echo-cancelling voice-communication input (off: the plain recognition input). */
    var echoCancellation: Boolean by Flag("echoCancellation", true)
    /** The player pointed once to where saved comments are (after the first one). */
    var commentsHintShown: Boolean by Flag("commentsHintShown", false)

    /** Voice chosen for on-device conversion, per narration language. */
    fun voiceIdentifier(language: String): String? = store.getString("voice.$language")

    fun setVoiceIdentifier(id: String, language: String) = store.putString("voice.$language", id)

    private inner class Flag(private val key: String, default: Boolean) {
        private val state = mutableStateOf(store.getBoolean(key, default))
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = state.value
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            state.value = value
            store.putBoolean(key, value)
        }
    }

    private inner class Number(private val key: String, default: Double) {
        private val state = mutableStateOf(store.getDouble(key, default))
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Double = state.value
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Double) {
            state.value = value
            store.putDouble(key, value)
        }
    }

    private inner class Choice<T>(private val key: String, default: T, values: List<T>, private val raw: (T) -> String) {
        private val state = mutableStateOf(store.getString(key)?.let { s -> values.firstOrNull { raw(it) == s } } ?: default)
        operator fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            store.putString(key, raw(value))
        }
    }
}

/**
 * The language comments are transcribed in. Comments are usually spoken in the
 * book's language, so that is the default.
 */
enum class CommentLanguage(val raw: String) {
    SAME_AS_BOOK("book"), ENGLISH("en"), GERMAN("de");

    val displayName: String get() = when (this) {
        SAME_AS_BOOK -> "Same as Book"
        ENGLISH -> VoiceCommand.Language.ENGLISH.displayName
        GERMAN -> VoiceCommand.Language.GERMAN.displayName
    }

    fun resolved(bookLanguage: String?): VoiceCommand.Language = when (this) {
        SAME_AS_BOOK -> VoiceCommand.Language.fromCode(bookLanguage)
        ENGLISH -> VoiceCommand.Language.ENGLISH
        GERMAN -> VoiceCommand.Language.GERMAN
    }
}

/** The palettes of the Apple apps, with the same stored keys (`gruvbox` is the hard-contrast one). */
enum class ColorTheme(val raw: String, val displayName: String) {
    GEIST_DARK("geistDark", "GEIST Dark"), GEIST("geist", "GEIST"), GRUVBOX("gruvbox", "Gruvbox Hard"),
    GRUVBOX_DARK("gruvboxDark", "Gruvbox Dark"), GRUVBOX_LIGHT("gruvboxLight", "Gruvbox Light"), STANDARD("standard", "Standard"),
}

/**
 * The face for the book's own words (sentences, chapter titles, quotes). The UI stays
 * in the UI face, so "the book" and "what I said" look different. Apple's faces don't
 * ship on Android; each choice keeps its iOS key and maps to the closest open font.
 */
enum class BookFont(val raw: String, val displayName: String) {
    NEW_YORK("newYork", "Source Serif"), CHARTER("charter", "Charis"), IOWAN("iowan", "Literata"), SANS("sans", "Inter"),
}
