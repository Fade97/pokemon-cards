// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

/** What to fetch for one language. */
sealed interface PackDownload {
    val entry: PackEntry

    /** Bytes to download, without the model. */
    val size: Long

    data class Full(override val entry: PackEntry) : PackDownload {
        override val size: Long get() = entry.size
    }

    /** The installed version is brought up to [entry] by [chain], applied in order. */
    data class Deltas(override val entry: PackEntry, val chain: List<PackDelta>) : PackDownload {
        override val size: Long get() = chain.sumOf { it.size }
    }
}

/** The result of comparing the catalog with what is installed and what the user wants. */
data class SyncPlan(
    /** The model every installed pack uses after the sync; null when no wanted language has a usable pack. */
    val model: PackModel?,
    /** The model files have to be (re)downloaded. */
    val downloadModel: Boolean,
    /**
     * The installed packs belong to another model. The old index does not fit the new model, so every language is
     * downloaded in full and everything switches at once (docs/packs.md, "Picking what to download").
     */
    val modelChange: Boolean,
    val downloads: Map<String, PackDownload>,
    /** Installed languages the user no longer wants. */
    val remove: Set<String>,
    /** Wanted languages without a usable pack for [model] in the catalog (yet). */
    val unavailable: Set<String>,
) {
    val isEmpty: Boolean get() = !downloadModel && downloads.isEmpty() && remove.isEmpty()
}

/** docs/packs.md, "Picking what to download". Pure, so every rule is unit-tested. */
object PackPlanner {
    /**
     * Full pack, delta chain, or null when [installed] is current. A pack an older app stored without the cards'
     * printings ([PackMeta.printingsKept]) is downloaded in full, even at the same version: deltas only carry the
     * cards that changed, so the others would never get theirs back.
     */
    fun pick(installed: PackMeta?, entry: PackEntry): PackDownload? {
        if (installed == null || !installed.modelSha256.equals(entry.modelSha256, ignoreCase = true)) return PackDownload.Full(entry)
        if (!installed.printingsKept) return PackDownload.Full(entry)
        if (installed.version == entry.version) return null
        // A catalog that went back (a publish was rolled back) can't be reached by deltas.
        if (installed.version > entry.version) return PackDownload.Full(entry)
        val chain = deltaChain(installed.version, entry) ?: return PackDownload.Full(entry)
        return if (chain.sumOf { it.size } < entry.size) PackDownload.Deltas(entry, chain) else PackDownload.Full(entry)
    }

    /** The deltas from [from] to `entry.version`, or null when a link is missing. */
    fun deltaChain(from: Int, entry: PackEntry): List<PackDelta>? {
        val byFrom = entry.deltas.associateBy { it.from }
        val chain = mutableListOf<PackDelta>()
        var v = from
        while (v < entry.version) {
            val next = byFrom[v]?.takeIf { it.to > v } ?: return null
            chain += next
            v = next.to
        }
        return chain.takeIf { v == entry.version }
    }

    /**
     * @param activeModel the installed model's id, null when none is installed.
     * @param modelOk false when the installed model files failed their check and must be fetched again.
     * @param forceFull languages to download in full regardless of their version (broken or repaired packs).
     */
    fun plan(
        catalog: PackCatalog,
        activeModel: String?,
        modelOk: Boolean,
        installed: Map<String, PackMeta>,
        wanted: Set<String>,
        forceFull: Set<String> = emptySet(),
    ): SyncPlan {
        val usable = wanted.mapNotNull { lang ->
            catalog.packs[lang]?.takeIf { it.usable && it.model in catalog.models }
        }
        val target = targetModel(usable.map { it.model!! }, activeModel)
        val model = target?.let(catalog.models::get)
        val modelChange = activeModel != null && target != null && target != activeModel
        val downloads = LinkedHashMap<String, PackDownload>()
        for (entry in usable.sortedBy { it.lang }) {
            if (entry.model != target) continue
            val download = if (modelChange || entry.lang in forceFull) PackDownload.Full(entry) else pick(installed[entry.lang], entry)
            if (download != null) downloads[entry.lang] = download
        }
        return SyncPlan(
            model = model,
            downloadModel = target != null && (target != activeModel || !modelOk),
            modelChange = modelChange,
            downloads = downloads,
            remove = installed.keys - wanted,
            unavailable = wanted - usable.filter { it.model == target }.map { it.lang }.toSet(),
        )
    }

    /**
     * One model serves all languages. While a new model is rolled out pack by pack the catalog can name two; then the
     * installed one stays until every wanted pack has moved, so the app never ends up with packs that don't fit
     * together. Without an installed model the one most packs use wins.
     */
    private fun targetModel(models: List<String>, activeModel: String?): String? {
        val distinct = models.toSet()
        return when {
            distinct.isEmpty() -> null
            distinct.size == 1 -> distinct.single()
            activeModel in distinct -> activeModel
            else -> models.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .first().key
        }
    }
}
