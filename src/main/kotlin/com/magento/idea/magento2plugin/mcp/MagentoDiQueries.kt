/**
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */

package com.magento.idea.magento2plugin.mcp

import com.intellij.openapi.project.Project
import com.magento.idea.magento2plugin.magento.files.ModuleDiXml

internal object MagentoDiQueries {
    /**
     * Scans DI XML files for preferences, type declarations, virtual types, and plugins tied to a class name.
     */
    fun findDiConfigForClass(project: Project, className: String): String {
        val query = MagentoMcpSupport.normalizeFqn(className)
        if (query.isEmpty()) {
            return "Provide a PHP class or virtual type name."
        }

        val snapshot = MagentoMcpSnapshots.diSnapshot(project)
        val preferencesFor = snapshot.preferencesFor[query].orEmpty().map { record ->
            "${record.filePath} -> preference for=${record.preferenceFor ?: "-"} type=${record.preferenceType ?: "-"}"
        }
        val preferencesType = snapshot.preferencesType[query].orEmpty().map { record ->
            "${record.filePath} -> preference for=${record.preferenceFor ?: "-"} type=${record.preferenceType ?: "-"}"
        }
        val typeDeclarations = snapshot.typeDeclarations[query].orEmpty().map { record ->
            val argumentSuffix = if (record.argumentNames.isEmpty()) {
                ""
            } else {
                " arguments=${record.argumentNames.joinToString(", ")}"
            }
            "${record.filePath} -> type name=${record.typeName}$argumentSuffix"
        }
        val virtualTypeDeclarations = (
            snapshot.virtualTypesByName[query].orEmpty() + snapshot.virtualTypesByTarget[query].orEmpty()
        ).distinct().map { record ->
            "${record.filePath} -> virtualType name=${record.name ?: "-"} type=${record.targetType ?: "-"}"
        }
        val pluginDeclarations = snapshot.pluginDeclarations[query].orEmpty().map { record ->
            "${record.filePath} -> plugin name=${record.pluginName} type=${record.pluginType ?: "-"} " +
                "sortOrder=${record.sortOrder} disabled=${record.disabled} scope=${record.scope}"
        }

        val lines = mutableListOf<String>()
        MagentoMcpSupport.addSection(lines, "preferences for class", preferencesFor)
        MagentoMcpSupport.addSection(lines, "preferences using class as implementation", preferencesType)
        MagentoMcpSupport.addSection(lines, "type declarations", typeDeclarations)
        MagentoMcpSupport.addSection(lines, "virtual type declarations", virtualTypeDeclarations)
        MagentoMcpSupport.addSection(lines, "plugin declarations", pluginDeclarations)

        if (lines.isEmpty()) {
            return "No DI configuration matched \"$query\"."
        }

        val truncated = listOf(preferencesFor, preferencesType, typeDeclarations, virtualTypeDeclarations, pluginDeclarations)
            .any { it.size > MagentoMcpSupport.MAX_MATCHES }
        val hint = if (truncated) "\n\n${MagentoInspectionResults.TEXT_TRUNCATED_HINT}" else ""
        return "DI configuration for \"$query\"\n\n${lines.joinToString("\n")}$hint"
    }

    /** Every di.xml declaration of the class, ordered by file, line and kind. */
    fun diConfigRecords(project: Project, className: String): List<InspectionRecord> {
        val query = MagentoMcpSupport.normalizeFqn(className)
        val snapshot = MagentoMcpSnapshots.diSnapshot(project)
        fun area(path: String) = MagentoMcpSupport.determineConfigScope(path, ModuleDiXml.FILE_NAME)
        fun preference(role: String, record: PreferenceRecord) = InspectionRecord("preference", query, record.filePath,
            record.line, area(record.filePath), linkedMapOf("role" to role, "for" to record.preferenceFor, "type" to record.preferenceType))
        return snapshot.preferencesFor[query].orEmpty().map { preference("for", it) } +
            snapshot.preferencesType[query].orEmpty().map { preference("type", it) } +
            snapshot.typeDeclarations[query].orEmpty().map {
                InspectionRecord("type", query, it.filePath, it.line, area(it.filePath),
                    linkedMapOf("name" to it.typeName, "arguments" to it.argumentNames))
            } +
            (snapshot.virtualTypesByName[query].orEmpty() + snapshot.virtualTypesByTarget[query].orEmpty()).distinct().map {
                InspectionRecord("virtualType", query, it.filePath, it.line, area(it.filePath),
                    linkedMapOf("name" to it.name, "type" to it.targetType))
            } +
            snapshot.pluginDeclarations[query].orEmpty().map {
                InspectionRecord("plugin", query, it.filePath, it.line, it.scope, linkedMapOf("name" to it.pluginName,
                    "type" to it.pluginType, "sortOrder" to it.sortOrder, "disabled" to it.disabled))
            }.let { records ->
                records.sortedWith(compareBy({ it.path }, { it.line }, { it.kind }, { it.fields["role"]?.toString() }))
            }
    }

    fun findPreferenceResolution(project: Project, className: String, area: String): String {
        val resolution = MagentoPreferenceResolution.resolve(project, className, area)
        if (resolution.candidates.isEmpty()) {
            return "No preference is declared for ${resolution.className} in global${if (area == "global") "" else " or $area"}."
        }
        val lines = mutableListOf(
            "Preference for ${resolution.className} in area $area: ${resolution.effectiveType ?: "unknown"}",
            "reason: ${resolution.reason}",
            "module order: ${resolution.order.path?.takeIf { resolution.order.problem == null } ?: "unknown (${resolution.order.problem})"}"
        )
        if (resolution.chain.size > 1) {
            lines += "chain: ${(listOf(resolution.className) + resolution.chain).joinToString(" -> ")}"
        }
        lines += ""
        lines += "Declarations in load order:"
        for (candidate in resolution.candidates.take(MagentoMcpSupport.MAX_MATCHES)) {
            val record = candidate.record
            lines += "- ${candidate.status}: ${record.preferenceType ?: "-"} " +
                "(${record.filePath}:${record.line}, ${candidate.module ?: "no module"}, " +
                "${MagentoMcpSupport.determineConfigScope(record.filePath, ModuleDiXml.FILE_NAME)})"
        }
        if (resolution.candidates.size > MagentoMcpSupport.MAX_MATCHES) {
            lines += ""
            lines += MagentoInspectionResults.TEXT_TRUNCATED_HINT
        }
        return lines.joinToString("\n")
    }

    /** One record per declaration in load order; the decision is in the reply's `resolution`. */
    fun preferenceRecords(resolution: MagentoPreferenceResolution.Resolution): List<InspectionRecord> =
        resolution.candidates.mapIndexed { index, candidate ->
            val record = candidate.record
            InspectionRecord("preference", resolution.className, record.filePath, record.line,
                MagentoMcpSupport.determineConfigScope(record.filePath, ModuleDiXml.FILE_NAME),
                linkedMapOf("type" to record.preferenceType, "status" to candidate.status, "loadOrder" to index + 1),
                moduleHint = candidate.module)
        }

    /**
     * Retains the existing query API while dispatching PHP semantics through the optional provider. Without
     * [area] it lists declarations of every area; with it, the plugins Magento runs there.
     */
    fun findPluginsForMethod(project: Project, className: String, methodName: String, area: String? = null): String {
        val provider = MagentoMethodPluginQueryProvider.EP_NAME.extensionList.firstOrNull()
            ?: return MagentoMethodPluginQueryProvider.UNAVAILABLE_MESSAGE
        if (area == null) {
            val text = provider.findPluginsForMethod(project, className, methodName)
            if (!text.startsWith("Found ")) return text
            // The note about cut results stays last, as the skill tells agents to look for it there.
            val truncated = text.endsWith(MagentoInspectionResults.TEXT_TRUNCATED_HINT)
            val body = text.removeSuffix(MagentoInspectionResults.TEXT_TRUNCATED_HINT).trimEnd()
            return "$body\n\n$PLUGIN_AREA_HINT" + if (truncated) "\n\n${MagentoInspectionResults.TEXT_TRUNCATED_HINT}" else ""
        }
        val result = try {
            (provider as? MagentoMethodPluginRecordProvider)?.pluginRecords(project, className, methodName, area)
        } catch (exception: InvalidInspectionRequest) {
            return exception.message.orEmpty()
        } ?: return "The registered `plugins_for_method` provider does not support `area`. Omit area to list the declarations of every area."
        return pluginAreaText(MagentoMcpSupport.normalizeFqn(className), methodName.trim(), area, result)
    }

    private const val PLUGIN_AREA_HINT =
        "These are declarations of every area; disabled and replaced declarations elsewhere are not applied. " +
            "Add `area` to get the plugins Magento runs in one area, with the reason for each one it does not run."

    private fun pluginAreaText(className: String, methodName: String, area: String, result: PluginRecords): String {
        val runs = result.records.filter { it.fields["status"] == "applies" }
        val undecided = result.records.filter { it.fields["status"] == "candidate" }
        val skipped = result.records - runs.toSet() - undecided.toSet()
        fun describe(record: InspectionRecord) =
            "${record.fields["type"]}::${record.fields["method"]}() as `${record.fields["name"]}` (${record.path}:${record.line}, ${record.area})"
        val scope = if (area == "global") "global" else "global or $area"
        val lines = mutableListOf("Plugins Magento runs for $className::$methodName() in area $area: ${runs.size} method(s).")
        runs.take(MagentoMcpSupport.MAX_MATCHES).forEach {
            lines += "- ${it.fields["pluginKind"]}, sortOrder ${it.fields["sortOrder"]}: ${describe(it)}"
        }
        if (undecided.isNotEmpty()) {
            lines += ""
            lines += "Undecided, may or may not run: ${undecided.size}."
            undecided.take(MagentoMcpSupport.MAX_MATCHES).forEach { lines += "- ${describe(it)}" }
        }
        if (skipped.isNotEmpty()) {
            lines += ""
            lines += "Declared for $scope but not run: ${skipped.size}."
            skipped.take(MagentoMcpSupport.MAX_MATCHES).forEach { record ->
                val by = record.fields["statusBy"]?.let { if (record.fields["status"] == "disabled") "; disabled by $it" else "; replaced by $it" }.orEmpty()
                lines += "- ${record.fields["status"]}: ${describe(record)}$by"
            }
        }
        result.resolution?.problem?.let { lines += ""; lines += it }
        result.resolution?.order?.path?.let { lines += "Module order: $it." }
        if (listOf(runs, undecided, skipped).any { it.size > MagentoMcpSupport.MAX_MATCHES }) {
            lines += ""
            lines += MagentoInspectionResults.TEXT_TRUNCATED_HINT
        }
        return lines.joinToString("\n")
    }

    /** Structured plugin matches, or null when the registered provider offers only text. */
    fun pluginRecords(project: Project, className: String, methodName: String, area: String? = null): PluginRecords? =
        (MagentoMethodPluginQueryProvider.EP_NAME.extensionList.firstOrNull() as? MagentoMethodPluginRecordProvider)
            ?.pluginRecords(project, className, methodName, area)
}
