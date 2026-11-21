package de.kaikunze.marginvoice.core

import java.time.Instant

/**
 * Three-way merge of one kind of item (books, or the comments of a book) between this
 * device and the sync store. `base` is what both sides held after the last sync (the
 * content hash per id), so a missing item tells a deletion from a new item without
 * tombstones:
 *
 * - changed on one side only: that side wins;
 * - changed on both: the newer `updatedAt` wins (ties: the larger hash, so every device picks the same);
 * - deleted on one side, unchanged on the other: deleted;
 * - deleted on one side, edited on the other: the edit survives (no comment is lost);
 * - in the store but unreadable (`unreadable`: a newer format, a damaged or half-uploaded file, a folder
 *   that is missing): left alone on both sides. Absence that is only an unreadable file is not a deletion.
 */
object SyncMerge {
    data class Item<T>(val value: T, val hash: String, val updatedAt: Instant)

    sealed interface Action<T> {
        val id: String
        data class PutLocal<T>(override val id: String, val value: T) : Action<T>
        data class DeleteLocal<T>(override val id: String) : Action<T>
        data class PutRemote<T>(override val id: String, val value: T) : Action<T>
        data class DeleteRemote<T>(override val id: String) : Action<T>
    }

    data class Result<T>(val actions: List<Action<T>>, val base: Map<String, String>)

    fun <T> merge(
        base: Map<String, String>, local: Map<String, Item<T>>, remote: Map<String, Item<T>>, unreadable: Set<String> = emptySet(),
    ): Result<T> {
        val actions = ArrayList<Action<T>>()
        val next = HashMap<String, String>()
        for (id in (base.keys + local.keys + remote.keys).sorted()) {
            val b = base[id]
            if (id in unreadable && remote[id] == null) {
                // keep what both sides agreed on last time, so a readable copy later merges as usual
                if (b != null) next[id] = b
                continue
            }
            val l = local[id]
            val r = remote[id]
            when {
                l == null && r == null -> {}
                l != null && r != null -> {
                    val winner = when {
                        l.hash == r.hash -> null
                        l.hash == b -> r
                        r.hash == b -> l
                        r.updatedAt != l.updatedAt -> if (r.updatedAt > l.updatedAt) r else l
                        else -> if (r.hash > l.hash) r else l
                    }
                    if (winner === r) actions.add(Action.PutLocal(id, r.value))
                    if (winner === l) actions.add(Action.PutRemote(id, l.value))
                    next[id] = (winner ?: l).hash
                }
                l != null -> if (b != null && b == l.hash) actions.add(Action.DeleteLocal(id)) else {
                    actions.add(Action.PutRemote(id, l.value))
                    next[id] = l.hash
                }
                r != null -> if (b != null && b == r.hash) actions.add(Action.DeleteRemote(id)) else {
                    actions.add(Action.PutLocal(id, r.value))
                    next[id] = r.hash
                }
            }
        }
        return Result(actions, next)
    }
}
