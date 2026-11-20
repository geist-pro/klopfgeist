package de.kaikunze.marginvoice.core

/**
 * Narrated papers to try the app with, as `.drafttalk` files on the site's "samples" release.
 * The ids are the books' ids, so a downloaded sample shows as already in the library.
 */
object SampleBooks {
    data class Sample(val id: String, val title: String, val credit: String, val duration: Double, val bytes: Long, val file: String) {
        val url: String get() = "$BASE/$file"
    }

    const val BASE = "https://github.com/kkai/marginvoice/releases/download/samples"

    val all = listOf(
        Sample(
            "5917169A-29A3-4208-84D2-2F9B303288F5", "Cuddle-Fish: Exploring a Soft Floating Robot with Flapping Wings for Physical Interactions",
            "Xu et al., Augmented Humans 2025", 3331.0, 51_850_196, "cuddle-fish.drafttalk",
        ),
        Sample(
            "4EF2CA9C-2240-4BDA-9F71-5135C549E9A0", "Human–Computer Co-Regulation: A Review of Biofeedback for Meditative Practice",
            "Liu, Peng et al., IMWUT 2026", 3721.0, 48_722_457, "co-regulation.drafttalk",
        ),
        Sample(
            "BABAFA6B-2813-44DB-9259-418526B704E6", "NASA-Task Load Index in CHI: A Comprehensive Review and Subscale Meta-Analysis",
            "Lee, Starner et al., TOCHI 2026", 6816.0, 88_156_758, "nasa-tlx.drafttalk",
        ),
    )

    /** "52 MB" */
    fun size(bytes: Long): String = "${(bytes + 500_000) / 1_000_000} MB"
}
