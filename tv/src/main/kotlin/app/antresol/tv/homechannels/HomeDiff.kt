package app.antresol.tv.homechannels

/**
 * A program the system already holds for this app: [id] is the provider's row id, [key] the internal provider id
 * we gave it and [fingerprint] the digest we kept in its first flag (null/blank for a row that is not ours to keep).
 */
data class StoredProgram(val id: Long, val key: String?, val fingerprint: Long?)

/** What to do to bring the stored programs in line with the wanted ones. */
data class Plan(
    val add: List<ProgramSpec> = emptyList(),
    val update: List<Pair<Long, ProgramSpec>> = emptyList(),
    val remove: List<Long> = emptyList(),
) {
    val isEmpty: Boolean get() = add.isEmpty() && update.isEmpty() && remove.isEmpty()
}

object HomeDiff {
    /**
     * Matches [stored] with [desired] by key:
     * - a wanted program that is not stored is added;
     * - one that is stored with another fingerprint is updated; with the same fingerprint it is left alone;
     * - a stored one that is no longer wanted is removed, and so are rows without a key and the extra rows of a key
     *   that is stored twice.
     *
     * A row the user dismissed on the launcher stays stored (hidden) while it is still wanted, so it is neither added
     * again nor shown again. With [rewriteAllOnChange], a plan with any addition or update also updates the unchanged
     * programs: Watch Next orders by the time of the last engagement, which is written with each program, so a list
     * that changed is written whole to keep its order.
     */
    fun plan(stored: List<StoredProgram>, desired: List<ProgramSpec>, rewriteAllOnChange: Boolean = false): Plan {
        val byKey = LinkedHashMap<String, StoredProgram>()
        val remove = ArrayList<Long>()
        for (row in stored) {
            val key = row.key
            if (key.isNullOrBlank() || byKey.containsKey(key)) remove += row.id else byKey[key] = row
        }
        val wanted = desired.distinctBy { it.key }
        val wantedKeys = wanted.mapTo(HashSet()) { it.key }
        byKey.values.filter { it.key !in wantedKeys }.forEach { remove += it.id }

        val add = ArrayList<ProgramSpec>()
        val update = ArrayList<Pair<Long, ProgramSpec>>()
        val kept = ArrayList<Pair<Long, ProgramSpec>>()
        for (spec in wanted) {
            val row = byKey[spec.key]
            when {
                row == null -> add += spec
                row.fingerprint != spec.fingerprint() -> update += row.id to spec
                else -> kept += row.id to spec
            }
        }
        if (rewriteAllOnChange && (add.isNotEmpty() || update.isNotEmpty())) {
            update += kept
            update.sortBy { it.second.rank }
        }
        return Plan(add, update, remove)
    }
}
