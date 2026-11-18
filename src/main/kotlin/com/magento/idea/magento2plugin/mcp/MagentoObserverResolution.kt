/*
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */
package com.magento.idea.magento2plugin.mcp

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project

/**
 * Decides which `<observer>` declarations Magento runs for an event in an area, following
 * `Magento\Framework\Event\Config\Reader`, `Converter` and `Magento\Framework\Config\Data\Scoped`:
 * - within one stage (global module files, then the area's module files) the files merge in module order by event
 *   and observer name, and a later attribute replaces an earlier one;
 * - the converter lowercases event names, so of two events in one stage that differ only by case the later one
 *   replaces the earlier one with all its observers;
 * - the area stage replaces the `instance` it declares, and its `disabled="true"` disables the observer, while
 *   `disabled="false"` there does not enable one that global disabled, because the converter drops it;
 * - an observer runs when it has an instance and is not disabled.
 */
internal object MagentoObserverResolution {
    /** What happened to one declaration; [by] is the declaration that decided it. */
    data class Fate(val status: String, val by: EventObserverRecord? = null)

    class Resolution(
        /** The event name as Magento dispatches it, in lower case. */
        val event: String,
        val area: String,
        val order: MagentoPreferenceResolution.ModuleOrder,
        /** Declarations of global and [area] in load order, each with its fate. */
        val declarations: List<Pair<EventObserverRecord, Fate>>,
        /** Why some declarations stay candidates or fail in Magento; null when there is nothing to say. */
        val problem: String?
    )

    /** Resolutions of [events], which are matched event names in any case, sharing one module order. */
    fun resolve(project: Project, events: List<String>, area: String): List<Resolution> {
        val owners = MagentoMcpSnapshots.moduleOwners(project)
        val byKey = MagentoMcpSnapshots.eventSnapshot(project).observersByEvent.entries.groupBy { it.key.lowercase() }
        val merge = Merge(area, MagentoPreferenceResolution.moduleOrder(project), owners,
            MagentoInspectionResults.rootModule(project, owners))
        return events.map { it.trim().lowercase() }.distinct().map { key ->
            merge.run(key, byKey[key].orEmpty().flatMap { (name, records) -> records.map { name to it } })
        }
    }

    internal class Merge(
        private val area: String,
        private val order: MagentoPreferenceResolution.ModuleOrder,
        private val owners: Map<String, String>,
        private val rootModule: MagentoInspectionResults.RootModuleFiles? = null
    ) {
        /** One loaded declaration with the exact event name it was declared under. */
        private class Item(val event: String, val record: EventObserverRecord, val place: MagentoPreferenceResolution.Placement)

        /** One observer name after merging; [disabledBy] is the declaration that disabled it. */
        private data class Entry(val type: EventObserverRecord?, val disabledBy: EventObserverRecord?) {
            /** The class that runs under this name: null when it is disabled, "" when it has no instance. */
            val outcome: String? get() = if (disabledBy != null) null else type?.declaredInstance.orEmpty()
        }

        private val fates = hashMapOf<EventObserverRecord, Fate>()

        /** [declarations] pairs each declaration of [event] with the event name it was declared under. */
        fun run(event: String, declarations: List<Pair<String, EventObserverRecord>>): Resolution {
            fates.clear()
            val placed = declarations.mapNotNull { (name, record) ->
                val place = MagentoPreferenceResolution.place(record.filePath, area, order, owners, rootModule, FILE_NAME)
                    ?: return@mapNotNull null
                Item(name, record, place)
            }.sortedWith(compareBy({ it.place.stage }, { it.place.position ?: -1 }, { it.record.filePath }, { it.record.line }))
            placed.filter { it.place.status != "candidate" }.forEach { fates[it.record] = Fate(it.place.status) }
            val loaded = placed.filter { it.place.status == "candidate" }

            val merged = merge(loaded, record = true)
            for (entry in merged.values) {
                val type = entry.type ?: continue
                fates[type] = when {
                    entry.disabledBy != null -> Fate("disabled", entry.disabledBy)
                    type.declaredInstance.isNullOrEmpty() -> Fate("merged")
                    else -> Fate("applies")
                }
            }
            val problems = mutableListOf<String>()
            if (!order.known) {
                val undecided = undecidedNames(loaded, merged)
                loaded.filter { it.record.observerName in undecided && it.record.declaredInstance != null }
                    .forEach { fates[it.record] = Fate("candidate") }
                if (undecided.isNotEmpty()) {
                    problems += "Whether and which class runs for the observers ${undecided.joinToString(", ")} depends on " +
                        "the module order, which is unknown: ${order.problem}"
                }
                val shared = loaded.groupBy { it.record.observerName }
                    .filter { (name, items) -> name !in undecided && items.mapTo(hashSetOf()) { it.place.module }.size > 1 }.keys
                if (shared.isNotEmpty()) {
                    problems += "The results of ${shared.sorted().joinToString(", ")} do not depend on the module order, " +
                        "but the declaration named in statusBy follows the path order."
                }
            }
            val missing = merged.filterValues { it.disabledBy == null && it.type?.declaredInstance.isNullOrEmpty() }.keys
                .filter { name -> loaded.none { it.record.observerName == name && fates[it.record]?.status == "candidate" } }
            if (missing.isNotEmpty()) {
                problems += "The observers ${missing.sorted().joinToString(", ")} have no instance after merging, so Magento " +
                    "fails when it dispatches this event."
            }
            return Resolution(event, area, order, placed.map { it.record to (fates[it.record] ?: Fate("merged")) },
                problems.joinToString(" ").ifEmpty { null })
        }

        /** Merges loaded declarations, already in load order, stage by stage. */
        private fun merge(items: List<Item>, record: Boolean): Map<String, Entry> {
            var merged = mapOf<String, Entry>()
            for (stage in items.groupBy { it.place.stage }.toSortedMap().values) merged = mergeStage(merged, stage, record)
            return merged
        }

        /**
         * Observer names whose outcome differs between module orders. One order applies to both stages. Observer
         * names merge independently unless a stage has event names that differ only by case, which replace each other
         * whole; then every name of the event is decided together. Above [MAX_PERMUTED_MODULES] modules a name
         * declared by several modules stays undecided.
         */
        private fun undecidedNames(items: List<Item>, found: Map<String, Entry>): Set<String> {
            val caseVariants = items.groupBy { it.place.stage }.values.any { stage -> stage.mapTo(hashSetOf()) { it.event }.size > 1 }
            val groups = if (caseVariants) listOf(items) else items.groupBy { it.record.observerName }.values
            val result = sortedSetOf<String>()
            for (group in groups) {
                val modules = group.mapTo(linkedSetOf()) { it.place.module }.toList()
                if (modules.size < 2) continue
                val names = group.mapTo(hashSetOf()) { it.record.observerName }
                if (modules.size > MAX_PERMUTED_MODULES) {
                    result += names
                    continue
                }
                for (permutation in permutations(modules)) {
                    ProgressManager.checkCanceled()
                    val rank = permutation.withIndex().associate { it.value to it.index }
                    val merged = merge(group.sortedWith(compareBy({ it.place.stage }, { rank.getValue(it.place.module) },
                        { it.record.filePath }, { it.record.line })), record = false)
                    names.filterTo(result) { merged[it]?.outcome != found[it]?.outcome }
                }
            }
            return result
        }

        /**
         * Merges one stage's declarations, in load order, onto [base]: the files merge as XML by event and observer
         * name, then the converted entries replace the keys they have.
         */
        private fun mergeStage(base: Map<String, Entry>, stage: List<Item>, record: Boolean): Map<String, Entry> {
            val byEvent = stage.groupBy { it.event }.values.toList()
            val kept = byEvent.last()
            if (record) {
                val replacedBy = kept.first().record
                byEvent.dropLast(1).flatten().forEach { fates[it.record] = Fate("overridden", replacedBy) }
            }
            val result = LinkedHashMap(base)
            for ((name, group) in kept.groupBy { it.record.observerName }) {
                var type: EventObserverRecord? = null
                var disabledBy: EventObserverRecord? = null
                for (item in group) {
                    item.record.declaredInstance?.let {
                        if (record && type != null) fates[type!!] = Fate("overridden", item.record)
                        type = item.record
                    }
                    item.record.declaredDisabled?.let { disabledBy = item.record.takeIf { _ -> it } }
                }
                val previous = result[name]
                if (record && previous?.type != null && type != null) fates[previous.type] = Fate("overridden", type)
                result[name] = Entry(type ?: previous?.type, disabledBy ?: previous?.disabledBy)
            }
            return result
        }

        private fun <T> permutations(items: List<T>): List<List<T>> =
            if (items.size < 2) listOf(items) else items.flatMap { first -> permutations(items - listOf(first)).map { listOf(first) + it } }
    }

    private const val FILE_NAME = "events.xml"

    /** Above this many modules for one observer name, the name stays undecided when the module order is unknown. */
    private const val MAX_PERMUTED_MODULES = 5
}
