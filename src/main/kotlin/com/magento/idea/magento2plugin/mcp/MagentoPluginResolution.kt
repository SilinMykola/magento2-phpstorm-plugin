/*
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */
package com.magento.idea.magento2plugin.mcp

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project

/**
 * Decides which `<plugin>` declarations Magento runs for a type in an area, following
 * `Magento\Framework\Interception\PluginListGenerator`:
 * - within one stage (app/etc/di.xml, global module files, the area's module files) declarations with the same
 *   plugin name merge in module order, and a later attribute replaces an earlier one;
 * - each later stage replaces the type and `disabled` it declares, and always its `sortOrder` (0 when absent);
 * - a class merges the plugin lists of its parents, then its own declarations replace them by name;
 * - a plugin with `disabled="true"` or without a class does not run; the rest run by sortOrder, then list position.
 *
 * Magento builds each type's list once and caches it. The first build returns the list without disabled plugins,
 * and the cached copy keeps them, so a plugin disabled on a parent can come back through another parent or a
 * child declaration depending on which types were built first. Names whose outcome differs between such build
 * orders, or between module orders when `app/etc/config.php` is unknown, are reported as candidates.
 * The type hierarchy comes from the caller, because it needs PHP.
 */
internal object MagentoPluginResolution {
    /** What happened to one declaration that names a plugin class; [by] is the declaration that decided it. */
    data class Fate(val status: String, val by: PluginDeclarationRecord? = null)

    class Resolution(
        val area: String,
        val order: MagentoPreferenceResolution.ModuleOrder,
        /** Declarations with a plugin class in global and [area]: applies, overridden, disabled, disabled_module, not_loaded or candidate. */
        val fates: Map<PluginDeclarationRecord, Fate>,
        /** The sortOrder Magento uses for each plugin that applies. */
        val sortOrders: Map<PluginDeclarationRecord, Int>,
        /** Position in Magento's sorted list, which orders plugins with the same sortOrder. */
        val positions: Map<PluginDeclarationRecord, Int>,
        /** Why some declarations stay candidates; null when everything was decided. */
        val problem: String?
    )

    fun resolve(project: Project, targetFqn: String, area: String, parents: (String) -> List<String>): Resolution {
        val order = MagentoPreferenceResolution.moduleOrder(project)
        val owners = MagentoMcpSnapshots.moduleOwners(project)
        val declarations = MagentoMcpSnapshots.diSnapshot(project).pluginDeclarations
        return Merge(area, order, owners, declarations, parents).run(MagentoMcpSupport.normalizeFqn(targetFqn))
    }

    internal class Merge(
        private val area: String,
        private val order: MagentoPreferenceResolution.ModuleOrder,
        private val owners: Map<String, String>,
        private val declarations: Map<String, List<PluginDeclarationRecord>>,
        parents: (String) -> List<String>
    ) {
        /**
         * One plugin name after merging; [disabledSet] says a declaration set `disabled`, and [disabledBy] is the one
         * that disabled it while it stays disabled.
         */
        private data class Entry(
            val type: PluginDeclarationRecord?,
            val sortOrder: Int,
            val disabledSet: Boolean,
            val disabledBy: PluginDeclarationRecord?
        ) {
            /** The class that runs, or null when nothing runs under this name. */
            val outcome: PluginDeclarationRecord? get() = type.takeIf { disabledBy == null }
        }

        private val parents = parents.memoized()
        private val fates = linkedMapOf<PluginDeclarationRecord, Fate>()
        private val inheritedCache = hashMapOf<String, Map<String, Entry>>()
        private val placedCache = hashMapOf<String, Map<Int, Map<String, List<PluginDeclarationRecord>>>>()
        private val variantCache = hashMapOf<Pair<String, Boolean>, Map<String, Set<Entry?>>>()

        /**
         * Decides the declarations for [target]. The fates come from one build order with every list cached; a name
         * whose outcome differs in another build or module order leaves every class it can run, and the declaration
         * of this order, as candidates.
         */
        fun run(target: String): Resolution {
            val result = inherited(target)
            val byBuild = variants(target, permuteModules = false)
            val byAnyOrder = if (order.known) byBuild else variants(target, permuteModules = true)
            val positions = hashMapOf<PluginDeclarationRecord, Int>()
            val sortOrders = hashMapOf<PluginDeclarationRecord, Int>()
            val buildNames = sortedSetOf<String>()
            val moduleNames = sortedSetOf<String>()
            val sortOrderNames = sortedSetOf<String>()
            val positionOf = result.keys.withIndex().associate { it.value to it.index }
            for (name in result.keys + byAnyOrder.keys) {
                val entry = result[name]
                val states = byAnyOrder[name].orEmpty()
                val outcomes = states.map { it?.outcome } + entry?.outcome
                if (outcomes.toSet().size > 1) {
                    if (byBuild[name].orEmpty().map { it?.outcome }.toSet().size > 1) buildNames += name else moduleNames += name
                    (outcomes.filterNotNull() + listOfNotNull(entry?.type, entry?.disabledBy?.takeIf { it.pluginType != null }))
                        .forEach { fates[it] = Fate("candidate") }
                    continue
                }
                val runs = entry?.outcome ?: continue
                if (states.mapNotNull { state -> state?.takeIf { it.outcome != null }?.sortOrder }.toSet().size > 1) sortOrderNames += name
                fates[runs] = Fate("applies")
                positions[runs] = positionOf.getValue(name)
                sortOrders[runs] = entry.sortOrder
            }
            val problems = mutableListOf<String>()
            if (moduleNames.isNotEmpty()) {
                problems += "Several modules declare plugins named ${moduleNames.joinToString(", ")} " +
                    "in the same stage, and the module order is unknown: ${order.problem}"
            }
            if (buildNames.isNotEmpty()) {
                problems += "Whether ${buildNames.joinToString(", ")} run depends on the order in which Magento first " +
                    "builds the plugin lists of the parent types: a plugin disabled on one parent comes back through another " +
                    "parent or a child declaration only when that parent's list was built before."
            }
            if (sortOrderNames.isNotEmpty()) {
                problems += "The sortOrder of ${sortOrderNames.joinToString(", ")} depends on the same orders, so their place " +
                    "among the plugins that run is not certain."
            }
            return Resolution(area, order, fates, sortOrders, positions, problems.joinToString(" ").ifEmpty { null })
        }

        /**
         * Plugins of [type] with a class, disabled ones included, as Magento caches them when every parent list was
         * cached before: the merged lists of its parents, then its own declarations replace them by name, sorted by
         * sortOrder.
         */
        private fun inherited(type: String): Map<String, Entry> {
            inheritedCache[type]?.let { return it }
            inheritedCache[type] = emptyMap()
            ProgressManager.checkCanceled()
            val merged = linkedMapOf<String, Entry>()
            for (parent in parents(type)) {
                for ((name, entry) in inherited(parent)) {
                    merged[name] = merge(merged[name], entry, record = true)
                }
            }
            for (names in placed(type).values) {
                for ((name, group) in names) {
                    merged[name] = merge(merged[name], dom(group, record = true), record = true)
                }
            }
            val result = linkedMapOf<String, Entry>()
            // Magento sorts each list before caching it with a stable sort, so children merge it in this order.
            merged.entries.filter { it.value.type != null }.sortedBy { it.value.sortOrder }.forEach { result[it.key] = it.value }
            for (entry in result.values) {
                entry.disabledBy?.let { decide(entry.type!!, "disabled", it) }
            }
            inheritedCache[type] = result
            return result
        }

        /**
         * Every state the cached list of [type] can hold for each plugin name, null meaning the name is absent: each
         * parent list comes either cached or, on its first build, without disabled plugins, and with [permuteModules]
         * the modules of one stage merge in any order.
         */
        private fun variants(type: String, permuteModules: Boolean): Map<String, Set<Entry?>> {
            val key = type to permuteModules
            variantCache[key]?.let { return it }
            variantCache[key] = emptyMap()
            ProgressManager.checkCanceled()
            val parentStates = parents(type).map { variants(it, permuteModules) }
            val stages = placed(type)
            val names = linkedSetOf<String>()
            parentStates.forEach { names += it.keys }
            stages.values.forEach { names += it.keys }
            val result = linkedMapOf<String, Set<Entry?>>()
            for (name in names) {
                var states: Set<Entry?> = setOf(null)
                for (parent in parentStates) {
                    val returned = parent[name] ?: continue
                    states = combine(states, returned.flatMapTo(hashSetOf()) { if (it?.disabledBy != null) listOf(it, null) else listOf(it) })
                }
                for (stage in stages.values) {
                    val group = stage[name] ?: continue
                    val orders = if (permuteModules && !order.known) moduleOrders(group) else listOf(group)
                    states = combine(states, orders.mapTo(hashSetOf()) { dom(it, record = false) })
                }
                val cached = states.mapTo(linkedSetOf()) { state -> state?.takeIf { it.type != null } }
                if (cached != setOf<Entry?>(null)) result[name] = cached
            }
            variantCache[key] = result
            return result
        }

        private fun combine(states: Set<Entry?>, incoming: Set<Entry?>): Set<Entry?> =
            states.flatMapTo(linkedSetOf()) { base -> incoming.map { if (it == null) base else merge(base, it, record = false) } }

        /** The same declarations merged with their modules in every order; a module's own files keep their order. */
        private fun moduleOrders(group: List<PluginDeclarationRecord>): List<List<PluginDeclarationRecord>> {
            val byModule = group.groupBy { owners.ownerOf(it.filePath) }.values.toList()
            if (byModule.size < 2 || byModule.size > MAX_PERMUTED_MODULES) return listOf(group)
            fun permutations(items: List<List<PluginDeclarationRecord>>): List<List<List<PluginDeclarationRecord>>> =
                if (items.size < 2) listOf(items) else items.flatMap { first -> permutations(items - listOf(first)).map { listOf(first) + it } }
            return permutations(byModule).map { it.flatten() }
        }

        /**
         * Loaded declarations of [type] by stage and plugin name, in load order; the stage is -1 for none. Declarations
         * that are not loaded get their status here.
         */
        private fun placed(type: String): Map<Int, Map<String, List<PluginDeclarationRecord>>> = placedCache.getOrPut(type) {
            declarations[type].orEmpty().mapNotNull { declaration ->
                val place = MagentoPreferenceResolution.place(declaration.filePath, area, order, owners) ?: return@mapNotNull null
                if (place.status != "candidate") {
                    if (declaration.pluginType != null) fates[declaration] = Fate(place.status)
                    return@mapNotNull null
                }
                declaration to place
            }
                .sortedWith(compareBy({ it.second.stage }, { it.second.position ?: -1 }, { it.first.filePath }, { it.first.line }))
                .groupBy({ it.second.stage }, { it.first })
                .toSortedMap()
                .mapValues { (_, stage) -> stage.groupBy { it.pluginName } }
        }

        /**
         * One stage's declarations of a plugin name merged as Magento merges the XML: only the attributes a file
         * declares replace earlier ones. The converted entry always has a sortOrder, 0 when no file declares it.
         */
        private fun dom(group: List<PluginDeclarationRecord>, record: Boolean): Entry {
            var type: PluginDeclarationRecord? = null
            var sortOrder: Int? = null
            var disabledSet = false
            var disabledBy: PluginDeclarationRecord? = null
            for (declaration in group) {
                if (declaration.pluginType != null) {
                    if (record && type != null) decide(type, "overridden", declaration)
                    type = declaration
                }
                sortOrder = declaration.declaredSortOrder ?: sortOrder
                declaration.declaredDisabled?.let { disabled ->
                    disabledSet = true
                    disabledBy = declaration.takeIf { disabled }
                }
            }
            return Entry(type, sortOrder ?: 0, disabledSet, disabledBy)
        }

        /** `array_replace_recursive` of one plugin entry: present keys replace, sortOrder is always present. */
        private fun merge(base: Entry?, incoming: Entry, record: Boolean): Entry {
            val previous = base?.type
            if (record && previous != null && incoming.type != null && previous !== incoming.type) {
                decide(previous, "overridden", incoming.type)
            }
            return Entry(
                incoming.type ?: previous,
                incoming.sortOrder,
                incoming.disabledSet || base?.disabledSet == true,
                if (incoming.disabledSet) incoming.disabledBy else base?.disabledBy
            )
        }

        /** The latest decision wins, so a declaration replaced at a child type is reported as overridden there. */
        private fun decide(declaration: PluginDeclarationRecord, status: String, by: PluginDeclarationRecord) {
            fates[declaration] = Fate(status, by)
        }

        private fun Map<String, String>.ownerOf(path: String): String? = MagentoInspectionResults.ownerOf(path, this)

        private fun ((String) -> List<String>).memoized(): (String) -> List<String> {
            val cache = hashMapOf<String, List<String>>()
            return { type -> cache.getOrPut(type) { this(type) } }
        }
    }

    /** Above this many modules for one plugin name in one stage, only the order found is merged. */
    private const val MAX_PERMUTED_MODULES = 5
}
