/**
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */

package com.magento.idea.magento2plugin.mcp

import com.intellij.openapi.project.Project

internal object MagentoEventQueries {
    /**
     * Finds event names via index lookup and expands them to observer declarations from matching events.xml files.
     */
    fun findObserversForEvent(project: Project, eventName: String, area: String? = null): String {
        val query = eventName.trim()
        if (query.isEmpty()) {
            return "Provide an event name."
        }
        if (area != null) {
            return areaText(project, query, area)
        }

        val snapshot = MagentoMcpSnapshots.eventSnapshot(project)
        val matchedEvents = MagentoMcpSupport.prioritizeMatches(
            snapshot.observersByEvent.keys,
            query
        )

        if (matchedEvents.isEmpty()) {
            return "No Magento events matched \"$query\"."
        }

        val lines = mutableListOf("Found ${matchedEvents.size} event match(es) for \"$query\".")
        var truncated = matchedEvents.size > MagentoMcpSupport.MAX_MATCHES
        for (matchedEvent in matchedEvents.take(MagentoMcpSupport.MAX_MATCHES)) {
            lines += ""
            lines += matchedEvent

            val observers = snapshot.observersByEvent[matchedEvent].orEmpty()
            if (observers.isEmpty()) {
                lines += "observers: none declared in events.xml"
                continue
            }

            truncated = truncated || observers.size > MagentoMcpSupport.MAX_FILE_MATCHES
            for (observer in observers.take(MagentoMcpSupport.MAX_FILE_MATCHES)) {
                lines += "file: ${observer.filePath} observer=${observer.observerName} instance=${observer.observerInstance} disabled=${observer.disabled}"
            }
        }

        lines += ""
        lines += AREA_HINT
        if (truncated) {
            lines += ""
            lines += MagentoInspectionResults.TEXT_TRUNCATED_HINT
        }
        return lines.joinToString("\n")
    }

    private const val AREA_HINT =
        "These are declarations of every area; disabled and replaced declarations elsewhere are not applied. " +
            "Add `area` to get the observers Magento runs in one area, with the reason for each one it does not run."

    /** Matching events as Magento dispatches them, in lower case, in match order. */
    private fun matchedEvents(project: Project, query: String): List<String> =
        MagentoMcpSupport.prioritizeMatches(MagentoMcpSnapshots.eventSnapshot(project).observersByEvent.keys, query)
            .map { it.lowercase() }.distinct()

    private fun areaText(project: Project, query: String, area: String): String {
        val events = matchedEvents(project, query)
        if (events.isEmpty()) {
            return "No Magento events matched \"$query\"."
        }
        val lines = mutableListOf("Found ${events.size} event match(es) for \"$query\" in area $area.")
        var truncated = events.size > MagentoMcpSupport.MAX_MATCHES
        var order: String? = null
        val scope = if (area == "global") "global" else "global or $area"
        for (resolution in MagentoObserverResolution.resolve(project, events.take(MagentoMcpSupport.MAX_MATCHES), area)) {
            val event = resolution.event
            order = resolution.order.path
            fun describe(record: EventObserverRecord) =
                "`${record.observerName}` ${record.declaredInstance?.ifEmpty { null } ?: "(no instance)"} (${record.filePath}:${record.line})"
            val groups = resolution.declarations.groupBy { (_, fate) ->
                when (fate.status) { "applies" -> 0; "candidate" -> 1; else -> 2 }
            }
            val runs = groups[0].orEmpty()
            val undecided = groups[1].orEmpty()
            val skipped = groups[2].orEmpty()
            lines += ""
            lines += "$event: Magento runs ${runs.size} observer(s) in area $area."
            runs.take(MagentoMcpSupport.MAX_FILE_MATCHES).forEach { (record, _) -> lines += "- ${describe(record)}" }
            if (undecided.isNotEmpty()) {
                lines += "Undecided, may or may not run: ${undecided.size}."
                undecided.take(MagentoMcpSupport.MAX_FILE_MATCHES).forEach { (record, _) -> lines += "- ${describe(record)}" }
            }
            if (skipped.isNotEmpty()) {
                lines += "Declared for $scope but not run: ${skipped.size}."
                skipped.take(MagentoMcpSupport.MAX_FILE_MATCHES).forEach { (record, fate) ->
                    val by = fate.by?.let { "; ${if (fate.status == "disabled") "disabled" else "replaced"} by ${it.filePath}:${it.line}" }.orEmpty()
                    lines += "- ${fate.status}: ${describe(record)}$by"
                }
            }
            resolution.problem?.let { lines += it }
            truncated = truncated || listOf(runs, undecided, skipped).any { it.size > MagentoMcpSupport.MAX_FILE_MATCHES }
        }
        order?.let { lines += ""; lines += "Module order: $it." }
        if (truncated) {
            lines += ""
            lines += MagentoInspectionResults.TEXT_TRUNCATED_HINT
        }
        return lines.joinToString("\n")
    }

    /**
     * Records of every matching event with [area], the declarations of global and that area with the status
     * Magento's merge gives them, keyed by the lower-case event name; with the problem of each undecided event.
     */
    fun observerResolution(project: Project, eventName: String, area: String): Pair<List<InspectionRecord>, List<MagentoObserverResolution.Resolution>> {
        val resolutions = MagentoObserverResolution.resolve(project, matchedEvents(project, eventName.trim()), area)
        return resolutions.flatMap { resolution ->
            resolution.declarations.map { (observer, fate) ->
                val fields = observerFields(observer)
                fields["status"] = fate.status
                fate.by?.let { fields["statusBy"] = "${it.filePath}:${it.line}" }
                InspectionRecord("observer", resolution.event, observer.filePath, observer.line,
                    MagentoMcpSupport.determineConfigScope(observer.filePath, "events.xml"), fields)
            }
        } to resolutions
    }

    private fun observerFields(observer: EventObserverRecord): LinkedHashMap<String, Any?> = linkedMapOf(
        "observerName" to observer.observerName,
        "instance" to observer.observerInstance,
        "disabled" to observer.disabled
    )

    /** Every observer of every matching event, one record each, in match order. */
    fun observerRecords(project: Project, eventName: String): List<InspectionRecord> {
        val snapshot = MagentoMcpSnapshots.eventSnapshot(project)
        return MagentoMcpSupport.prioritizeMatches(snapshot.observersByEvent.keys, eventName.trim()).flatMap { event ->
            snapshot.observersByEvent[event].orEmpty().map { observer ->
                InspectionRecord(
                    kind = "observer",
                    key = event,
                    path = observer.filePath,
                    line = observer.line,
                    area = MagentoMcpSupport.determineConfigScope(observer.filePath, "events.xml"),
                    fields = observerFields(observer)
                )
            }
        }
    }
}
