package de.kaikunze.marginvoice.core

import kotlin.math.abs

/**
 * Finds where a short transcript of book audio sits in the narration text.
 *
 * Used to refine a comment anchor: the ~20 s of book audio before the comment is
 * transcribed on device and aligned against the text around the estimated offset.
 * Local alignment (Smith–Waterman) over normalized word tokens tolerates recognizer
 * errors, dropped words and a cut-off first or last word.
 */
object ContextAligner {
    data class Result(
        /** Character offset just after the last matched word. */
        val offset: Int,
        /** Matched text words (indices into `NarrationText.words`). */
        val wordRange: IntRange,
        /** Fraction of transcript tokens that matched a text word, 0...1. */
        val confidence: Double,
    )

    private const val MATCH = 2
    private const val MISMATCH = -1
    private const val GAP = -1

    /**
     * @param transcript recognizer output for the audio that ends at the anchor.
     * @param estimate estimated character offset of the anchor.
     * @param window characters searched on each side of `estimate`.
     * @param minimumMatches fewer matched words than this returns null.
     */
    fun align(transcript: String, text: NarrationText, near: Int, window: Int = 2_000, minimumMatches: Int = 3): Result? {
        val estimate = near
        val query = TextSegmentation.tokens(transcript)
        if (query.isEmpty() || text.words.isEmpty()) return null
        val lo = text.wordIndex(atOrAfter = maxOf(0, estimate - window))
        val hi = minOf(text.words.size, text.wordIndex(atOrAfter = estimate + window))
        if (lo >= hi) return null
        val target = text.words.subList(lo, hi).map { it.normalized }

        val m = query.size
        val n = target.size
        // score and traceback matrices, (m+1) x (n+1), row-major
        val score = IntArray((m + 1) * (n + 1))
        val matches = IntArray((m + 1) * (n + 1))
        val start = IntArray((m + 1) * (n + 1))
        fun idx(i: Int, j: Int) = i * (n + 1) + j

        var bestScore = 0
        var bestJ = 0
        var bestMatches = 0
        var bestStart = 0
        var bestDistance = Int.MAX_VALUE
        for (i in 1..m) {
            for (j in 1..n) {
                val same = wordsMatch(query[i - 1], target[j - 1])
                val diag = score[idx(i - 1, j - 1)] + if (same) MATCH else MISMATCH
                val up = score[idx(i - 1, j)] + GAP
                val left = score[idx(i, j - 1)] + GAP
                val s = maxOf(0, diag, up, left)
                val k = idx(i, j)
                score[k] = s
                if (s <= 0) { start[k] = j; continue }
                if (s == diag) {
                    val p = idx(i - 1, j - 1)
                    matches[k] = matches[p] + if (same) 1 else 0
                    start[k] = if (score[p] == 0) j - 1 else start[p]
                } else if (s == up) {
                    matches[k] = matches[idx(i - 1, j)]
                    start[k] = start[idx(i - 1, j)]
                } else {
                    matches[k] = matches[idx(i, j - 1)]
                    start[k] = start[idx(i, j - 1)]
                }
                // prefer the highest score; on ties, the end closest to the estimate
                val endOffset = text.words[lo + j - 1].range.upper
                val distance = abs(endOffset - estimate)
                if (s > bestScore || (s == bestScore && distance < bestDistance)) {
                    bestScore = s; bestJ = j; bestMatches = matches[k]; bestStart = start[k]
                    bestDistance = distance
                }
            }
        }
        if (bestScore <= 0 || bestMatches < minOf(minimumMatches, m)) return null
        val first = lo + bestStart
        val last = lo + bestJ // exclusive
        return Result(
            offset = text.words[last - 1].range.upper,
            wordRange = first until last,
            confidence = minOf(1.0, bestMatches.toDouble() / m),
        )
    }

    /** Exact match, or one edit apart for words of five or more letters. */
    internal fun wordsMatch(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 5 || b.length < 5 || abs(a.length - b.length) > 1) return false
        return editDistance(a, b, limit = 1) <= 1
    }

    internal fun editDistance(a: String, b: String, limit: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > limit) return limit + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
