/*
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */
package com.magento.idea.magento2plugin.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.magento.idea.magento2plugin.magento.files.ModuleDiXml
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Path

/**
 * Decides which `<preference>` Magento applies for a type in an area, from the XML declarations and
 * the module order in `app/etc/config.php`. Magento loads `app/etc/di.xml`, then each enabled module's
 * `etc/di.xml` in that order, then each module's `etc/<area>/di.xml`; the last declaration wins.
 */
internal object MagentoPreferenceResolution {
    private const val MAX_CHAIN = 10
    private val MODULES_BLOCK = Regex("""['"]modules['"]\s*=>\s*(?:\[|array\s*\()""")
    private val MODULE_ENTRY = Regex("""['"]([A-Za-z0-9]+_[A-Za-z0-9]+)['"]\s*=>\s*([01])""")
    private val AREA = Regex("[a-z][a-z_]*")

    /** Enabled state and position of each module in `app/etc/config.php`; [path] is null when it was not found. */
    data class ModuleOrder(val path: String?, val modules: Map<String, Int>, val enabled: Set<String>, val problem: String?) {
        /** Whether the order can decide between modules. */
        val known: Boolean get() = path != null && problem == null
    }

    data class Candidate(
        val record: PreferenceRecord,
        val module: String?,
        /** applies, overridden, disabled_module, not_loaded, or candidate when the order is unknown. */
        val status: String,
        /** 0 for app/etc/di.xml, 1 for global module files, 2 for the area's module files. */
        val stage: Int,
        val position: Int?
    )

    data class Resolution(
        val className: String,
        val area: String,
        val candidates: List<Candidate>,
        /** The class Magento instantiates, after following preferences of the winning type; null when unknown. */
        val effectiveType: String?,
        val chain: List<String>,
        val reason: String,
        val order: ModuleOrder
    ) {
        fun summary(): JSONObject = JSONObject()
            .put("area", area)
            .put("effectiveType", effectiveType ?: JSONObject.NULL)
            .put("chain", JSONArray(chain))
            .put("reason", reason)
            .put("moduleOrderSource", order.path ?: JSONObject.NULL)
    }

    fun validArea(area: String): Boolean = AREA.matches(area)

    fun resolve(project: Project, className: String, area: String): Resolution {
        val query = MagentoMcpSupport.normalizeFqn(className)
        val order = moduleOrder(project)
        val owners = MagentoMcpSnapshots.moduleOwners(project)
        val rootModule = MagentoInspectionResults.rootModule(project, owners)
        val preferences = MagentoMcpSnapshots.diSnapshot(project).preferencesFor
        fun decideFor(type: String) = decide(preferences[type].orEmpty(), area, order, owners, rootModule)
        val first = decideFor(query)
        val chain = mutableListOf<String>()
        var reason = first.reason
        var next = first.winnerType
        var known = first.winnerType != null || first.candidates.none { it.status == "candidate" }
        while (next != null) {
            if (next == query || next in chain || chain.size == MAX_CHAIN) {
                reason += " The preferences of $next form a cycle or a chain longer than $MAX_CHAIN, so the result is unknown."
                known = false
                break
            }
            chain += next
            val step = decideFor(next)
            if (step.winnerType == null && step.candidates.any { it.status == "candidate" }) {
                reason += " $next has preferences of its own, and they cannot be decided: ${step.reason}"
                known = false
                break
            }
            next = step.winnerType
        }
        if (known && chain.size > 1) {
            reason += " ${chain.first()} has its own preference, so Magento creates ${chain.last()}."
        }
        return Resolution(query, area, first.candidates, chain.lastOrNull()?.takeIf { known }, chain, reason, order)
    }

    /** [winnerType] is set when the result is known, even if no single declaration can be named as the winner. */
    private data class Decision(val candidates: List<Candidate>, val winnerType: String?, val reason: String)

    /** Candidates for one type in global and [area], ordered as Magento loads them. */
    private fun decide(
        records: List<PreferenceRecord>,
        area: String,
        order: ModuleOrder,
        owners: Map<String, String>,
        rootModule: MagentoInspectionResults.RootModuleFiles?
    ): Decision {
        val candidates = records.mapNotNull { record ->
            val place = place(record.filePath, area, order, owners, rootModule) ?: return@mapNotNull null
            Candidate(record, place.module, place.status, place.stage, place.position)
        }
        if (candidates.isEmpty()) {
            return Decision(emptyList(), null, "No preference is declared for this type in global${if (area == "global") "" else " or $area"}.")
        }
        val known = order.known
        val loadable = candidates.filter { it.status == "candidate" }
        val loadOrder = compareBy<Candidate>({ it.stage }, { it.position ?: -1 }, { it.record.filePath }, { it.record.line })
        val winner = if (known && loadable.isNotEmpty()) loadable.maxWith(loadOrder) else null
        val agreed = loadable.map { it.record.preferenceType }.distinct().singleOrNull()
        val reason = when {
            loadable.isEmpty() -> "No declaration is loaded: every candidate belongs to a disabled or unknown module."
            !known && agreed == null -> "The candidates name different classes, and the module order is unknown: ${order.problem}"
            winner == null -> "Every loaded candidate names the same class, so the unknown module order does not matter (${order.problem})."
            winner.stage == 2 -> "The $area declaration overrides global ones; among them, ${winner.module} loads last in ${order.path}."
            winner.stage == 1 && loadable.size == 1 -> "It is the only loaded declaration."
            winner.stage == 1 -> "${winner.module} loads last in ${order.path} among the modules that declare it."
            else -> "Only app/etc/di.xml declares it."
        }
        val decided = candidates.map {
            when {
                it.status != "candidate" || winner == null -> it
                it === winner -> it.copy(status = "applies")
                else -> it.copy(status = "overridden")
            }
        }.sortedWith(loadOrder)
        return Decision(decided, winner?.record?.preferenceType ?: agreed?.takeIf { !known }, reason)
    }

    /** Where a `di.xml` declaration loads; [status] is not_loaded, disabled_module, or candidate when it loads. */
    data class Placement(val module: String?, val stage: Int, val position: Int?, val status: String)

    /**
     * Load position of a declaration in [area]: stage 0 for app/etc/di.xml, 1 for global module files,
     * 2 for the area's module files; null for files of other areas.
     */
    fun place(
        filePath: String,
        area: String,
        order: ModuleOrder,
        owners: Map<String, String>,
        rootModule: MagentoInspectionResults.RootModuleFiles? = null
    ): Placement? {
        val scope = MagentoMcpSupport.determineConfigScope(filePath, ModuleDiXml.FILE_NAME)
        val directory = filePath.substringBeforeLast('/', "")
        val primary = filePath.endsWith("app/etc/" + ModuleDiXml.FILE_NAME) && owners.ownerOf(filePath) == null
        // Magento reads <root>/app/etc/di.xml; copies such as vendor/magento/magento2-base/app/etc/di.xml are not loaded.
        val primaryDirectory = order.path?.substringBeforeLast('/')
        val loadedPrimary = if (primaryDirectory != null) directory == primaryDirectory else "vendor" !in directory.split('/')
        if (primary) {
            return if (loadedPrimary) Placement(null, 0, -1, "candidate") else Placement(null, -1, null, "not_loaded")
        }
        val stage = when (scope) {
            "global" -> 1
            area -> 2
            else -> return null
        }
        val moduleDirectory = MagentoInspectionResults.ownerDirectory(filePath, owners, rootModule)
        val module = moduleDirectory?.let(owners::get)
        // Magento reads only <module>/etc/di.xml and <module>/etc/<area>/di.xml, not copies such as test fixtures.
        val inModule = filePath.removePrefix("$moduleDirectory/")
        val expected = if (stage == 1) "etc/" + ModuleDiXml.FILE_NAME else "etc/$area/" + ModuleDiXml.FILE_NAME
        val status = when {
            module == null -> "not_loaded"
            inModule != expected -> "not_loaded"
            order.known && module !in order.modules -> "not_loaded"
            order.known && module !in order.enabled -> "disabled_module"
            else -> "candidate"
        }
        return Placement(module, stage, module?.let(order.modules::get), status)
    }

    /** Reads module order from the `app/etc/config.php` of the configured Magento root. */
    fun moduleOrder(project: Project): ModuleOrder {
        val files = FilenameIndex.getVirtualFilesByName("config.php", GlobalSearchScope.projectScope(project))
            .filter { it.path.endsWith("/app/etc/config.php") }
        val expected = MagentoMcpProjectContext.magentoRoot(project)?.resolve("app/etc/config.php")?.normalize()
        val file = if (expected != null) {
            files.firstOrNull { Path.of(it.path).normalize() == expected }
        } else {
            files.singleOrNull()
        } ?: return ModuleOrder(null, emptyMap(), emptySet(),
            if (expected != null) "$expected was not found." else "app/etc/config.php was not found or is not unique, and no Magento root is configured.")
        return parse(MagentoMcpSupport.relativePath(project, file), project, file)
    }

    private fun parse(path: String, project: Project, file: VirtualFile): ModuleOrder {
        // PSI text, like the di.xml snapshot, so unsaved edits of both are read the same way.
        val text = PsiManager.getInstance(project).findFile(file)?.text
            ?: return ModuleOrder(path, emptyMap(), emptySet(), "$path cannot be read.")
        val start = MODULES_BLOCK.find(text)
            ?: return ModuleOrder(path, emptyMap(), emptySet(), "$path has no static `modules` list.")
        val block = topLevel(text, start.range.last + 1)
            ?: return ModuleOrder(path, emptyMap(), emptySet(), "$path has an unterminated `modules` list.")
        val modules = linkedMapOf<String, Int>()
        val enabled = linkedSetOf<String>()
        for (match in MODULE_ENTRY.findAll(block)) {
            // Like PHP, a repeated key keeps its first position and takes the last value.
            val name = match.groupValues[1]
            modules.putIfAbsent(name, modules.size)
            if (match.groupValues[2] == "1") enabled += name else enabled -= name
        }
        return ModuleOrder(path, modules, enabled, if (modules.isEmpty()) "$path lists no modules." else null)
    }

    /**
     * The array body that starts at [from], with comments and nested arrays blanked out; null when the
     * closing bracket is missing.
     */
    private fun topLevel(text: String, from: Int): String? {
        val out = StringBuilder()
        var depth = 1
        var index = from
        while (index < text.length) {
            val char = text[index]
            val lineComment = text.startsWith("//", index) || char == '#'
            when {
                lineComment -> { index = text.indexOf('\n', index).let { if (it < 0) text.length else it }; continue }
                text.startsWith("/*", index) -> { index = text.indexOf("*/", index + 2).let { if (it < 0) text.length else it + 2 }; continue }
                char == '\'' || char == '"' -> {
                    val end = generateSequence(index + 1) { it + 1 }.takeWhile { it < text.length }
                        .firstOrNull { text[it] == char && text[it - 1] != '\\' } ?: return null
                    if (depth == 1) out.append(text, index, end + 1)
                    index = end + 1
                    continue
                }
                char == '[' || char == '(' -> depth++
                char == ']' || char == ')' -> if (--depth == 0) return out.toString()
            }
            if (depth == 1) out.append(char)
            index++
        }
        return null
    }

    private fun Map<String, String>.ownerOf(path: String): String? = MagentoInspectionResults.ownerOf(path, this)
}
