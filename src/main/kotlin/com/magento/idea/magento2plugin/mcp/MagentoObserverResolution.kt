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
        /** Why some declarations stay candidates; null when everything was decided. */
        val problem: String?
    )

    fun resolve(project: Project, event: String, area: String): Resolution {
        val key = event.trim().lowercase()
        val owners = MagentoMcpSnapshots.moduleOwners(project)
        val declarations = MagentoMcpSnapshots.eventSnapshot(project).observersByEvent
            .filterKeys { it.lowercase() == key }
            .flatMap { (name, records) -> records.map { name to it } }
        return Merge(area, MagentoPreferenceResolution.moduleOrder(project), owners,
            MagentoInspectionResults.rootModule(project, owners)).run(key, declarations)
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
            /** The declaration whose instance runs, or null when nothing runs under this name. */
            val outcome: EventObserverRecord? get() = type.takeIf { disabledBy == null }
        }

        private val fates = hashMapOf<EventObserverRecord, Fate>()

        /** [declarations] pairs each declaration with the event name it was declared under. */
        fun run(event: String, declarations: List<Pair<String, EventObserverRecord>>): Resolution {
            val placed = declarations.mapNotNull { (name, record) ->
                val place = MagentoPreferenceResolution.place(record.filePath, area, order, owners, rootModule, FILE_NAME)
                    ?: return@mapNotNull null
                Item(name, record, place)
            }.sortedWith(compareBy({ it.place.stage }, { it.place.position ?: -1 }, { it.record.filePath }, { it.record.line }))
            placed.filter { it.place.status != "candidate" }.forEach { fates[it.record] = Fate(it.place.status) }
            val stages = placed.filter { it.place.status == "candidate" }.groupBy { it.place.stage }.toSortedMap().values.toList()

            var merged = mapOf<String, Entry>()
            for (stage in stages) merged = mergeStage(merged, stage, record = true)
            for (entry in merged.values) {
                val type = entry.type ?: continue
                fates[type] = entry.disabledBy?.let { Fate("disabled", it) } ?: Fate("applies")
            }
            val undecided = if (order.known) emptySet() else undecidedNames(stages, merged)
            for (item in stages.flatten()) {
                if (item.record.observerName in undecided && item.record.declaredInstance != null) {
                    fates[item.record] = Fate("candidate")
                }
            }
            val problem = undecided.takeIf { it.isNotEmpty() }?.let {
                "Several modules declare observers named ${it.sorted().joinToString(", ")} for this event in the same " +
                    "stage, and the module order is unknown: ${order.problem}"
            }
            return Resolution(event, area, order, placed.map { it.record to (fates[it.record] ?: Fate("merged")) }, problem)
        }

        /** Names whose outcome differs between module orders; above [MAX_PERMUTED_MODULES] every shared name. */
        private fun undecidedNames(stages: List<List<Item>>, found: Map<String, Entry>): Set<String> {
            val result = sortedSetOf<String>()
            val orders = stages.map { stage ->
                val byModule = stage.groupBy { it.place.module }.values.toList()
                if (byModule.size > MAX_PERMUTED_MODULES) {
                    result += stage.groupBy { it.record.observerName }
                        .filterValues { items -> items.mapTo(hashSetOf()) { it.place.module }.size > 1 }.keys
                    listOf(stage)
                } else {
                    permutations(byModule).map { it.flatten() }
                }
            }
            fun visit(index: Int, merged: Map<String, Entry>) {
                ProgressManager.checkCanceled()
                if (index == orders.size) {
                    (merged.keys + found.keys).filterTo(result) { merged[it]?.outcome !== found[it]?.outcome }
                    return
                }
                orders[index].forEach { visit(index + 1, mergeStage(merged, it, record = false)) }
            }
            visit(0, emptyMap())
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

    /** Above this many modules in one stage, observer names they share are reported as candidates. */
    private const val MAX_PERMUTED_MODULES = 5
}
