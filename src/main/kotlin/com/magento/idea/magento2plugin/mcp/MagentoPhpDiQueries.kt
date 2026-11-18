/**
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */

package com.magento.idea.magento2plugin.mcp

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.util.indexing.FileBasedIndex
import com.jetbrains.php.PhpIndex
import com.magento.idea.magento2plugin.magento.files.ModuleDiXml

internal object MagentoPhpDiQueries {
    /**
     * Resolves the target class hierarchy and reports plugin methods that match the requested method name.
     */
    fun findPluginsForMethod(project: Project, className: String, methodName: String): String {
        val targetClassName = MagentoMcpSupport.normalizeFqn(className)
        val targetMethodName = methodName.trim()
        val sorted = try {
            pluginMatches(project, targetClassName, targetMethodName).first
        } catch (exception: InvalidInspectionRequest) {
            return exception.message.orEmpty()
        }
        if (sorted.isEmpty()) {
            return "No plugins matched ${targetClassName}::${targetMethodName}()."
        }

        val lines = mutableListOf(
            "Found ${sorted.size} plugin method match(es) for ${targetClassName}::${targetMethodName}()."
        )
        for (match in sorted.take(MagentoMcpSupport.MAX_MATCHES)) {
            lines += ""
            lines += "${match.kind} plugin"
            lines += "target: ${match.targetFqn}::${targetMethodName}()"
            lines += "pluginClass: ${match.pluginClass.presentableFQN}"
            lines += "pluginMethod: ${match.method.name}()"
            lines += "scope: ${match.scope}"
            lines += "sortOrder: ${match.sortOrder}"
            lines += "file: ${MagentoMcpSupport.relativePath(project, match.method.containingFile.virtualFile)}"
        }
        if (sorted.size > MagentoMcpSupport.MAX_MATCHES) {
            lines += ""
            lines += MagentoInspectionResults.TEXT_TRUNCATED_HINT
        }

        return lines.joinToString("\n")
    }

    /**
     * One record per matching plugin method, located at its `di.xml` declaration so the module and path
     * filters apply; the plugin method's own file and line are fields. With [area], only declarations of
     * global and that area are listed, each with the status Magento's merge gives it there.
     */
    fun pluginRecords(project: Project, className: String, methodName: String, area: String? = null): PluginRecords {
        val targetClassName = MagentoMcpSupport.normalizeFqn(className)
        val targetMethodName = methodName.trim()
        val key = "$targetClassName::$targetMethodName"
        val lineIndexes = mutableMapOf<String, MagentoMcpSupport.LineIndex>()
        val (matches, resolution) = pluginMatches(project, targetClassName, targetMethodName, area)
        return PluginRecords(matches.map { match ->
            val methodFile = match.method.containingFile
            val methodPath = MagentoMcpSupport.relativePath(project, methodFile.virtualFile)
            val methodLines = lineIndexes.getOrPut(methodPath) { MagentoMcpSupport.LineIndex(methodFile.viewProvider.contents) }
            val fields = linkedMapOf<String, Any?>(
                "name" to match.data.pluginName,
                "type" to match.pluginClass.presentableFQN,
                "method" to match.method.name,
                "pluginKind" to match.kind,
                "target" to match.targetFqn,
                "sortOrder" to (resolution?.sortOrders?.get(match.data) ?: match.sortOrder),
                "methodPath" to methodPath,
                "methodLine" to methodLines.lineOf(match.method)
            )
            resolution?.fates?.get(match.data)?.let { fate ->
                fields["status"] = fate.status
                fate.by?.let { fields["statusBy"] = "${it.filePath}:${it.line}" }
            }
            InspectionRecord("plugin", key, match.data.filePath, match.data.line, match.scope, fields)
        }, resolution)
    }

    private fun resolve(project: Project, targetClassName: String, area: String): MagentoPluginResolution.Resolution {
        val phpIndex = PhpIndex.getInstance(project)
        return MagentoPluginResolution.resolve(project, targetClassName, area) {
            MagentoPhpInspectionSupport.magentoParents(phpIndex, it)
        }
    }

    /**
     * Plugin methods of the target hierarchy in the order of the text reply. With [area], the declarations
     * come from the area's merge and include those Magento does not run, which sort after the ones it runs.
     */
    private fun pluginMatches(
        project: Project,
        targetClassName: String,
        targetMethodName: String,
        area: String? = null
    ): Pair<List<MagentoPhpInspectionSupport.PluginMethodMatch>, MagentoPluginResolution.Resolution?> {
        if (targetClassName.isEmpty() || targetMethodName.isEmpty()) {
            throw InvalidInspectionRequest("Provide both a target class name and method name.")
        }

        val phpIndex = PhpIndex.getInstance(project)
        val targetClasses = MagentoPhpInspectionSupport.resolveTargetClasses(phpIndex, targetClassName)
        if (targetClasses.isEmpty()) {
            throw InvalidInspectionRequest("Could not resolve target class \"$targetClassName\".")
        }

        val allTargetFqns = linkedSetOf(targetClassName)
        targetClasses.forEach { MagentoPhpInspectionSupport.collectClassHierarchy(it, allTargetFqns) }
        val resolution = area?.let { resolve(project, targetClassName, it) }
        val pluginDeclarations = resolution?.fates?.keys?.groupBy { it.targetFqn }
            ?: findPluginDeclarations(project, allTargetFqns)
        val pluginCandidates = mutableListOf<MagentoPhpInspectionSupport.PluginMethodMatch>()

        for (targetFqn in allTargetFqns) {
            for (pluginDeclaration in pluginDeclarations[targetFqn].orEmpty()) {
                if (resolution == null && pluginDeclaration.disabled) {
                    continue
                }
                val pluginType = pluginDeclaration.pluginType ?: continue
                for (pluginClass in phpIndex.getClassesByFQN(pluginType)) {
                    for (method in pluginClass.methods) {
                        val pluginKind = MagentoMcpSupport.getPluginKind(method.name, targetMethodName) ?: continue
                        if (!method.access.isPublic) {
                            continue
                        }
                        pluginCandidates += MagentoPhpInspectionSupport.PluginMethodMatch(
                            targetFqn,
                            pluginDeclaration,
                            pluginClass,
                            method,
                            pluginKind
                        )
                    }
                }
            }
        }

        val distinct = pluginCandidates
            .distinctBy { "${it.targetFqn}:${it.pluginClass.fqn}:${it.method.name}:${it.scope}:${it.sortOrder}:${if (resolution == null) "" else "${it.data.filePath}:${it.data.line}"}" }
        val matches = if (resolution == null) {
            distinct.sortedWith(
                compareBy<MagentoPhpInspectionSupport.PluginMethodMatch>(
                    { MagentoMcpSupport.pluginKindOrder(it.kind) },
                    { MagentoMcpSupport.configScopeOrder(it.scope) },
                    { it.sortOrder },
                    { it.pluginClass.presentableFQN },
                    { it.method.name }
                )
            )
        } else {
            // Plugins that run come first in Magento's order: sortOrder, then position in the merged list.
            distinct.sortedWith(
                compareBy<MagentoPhpInspectionSupport.PluginMethodMatch>(
                    { if (resolution.fates[it.data]?.status == "applies") 0 else 1 },
                    { MagentoMcpSupport.pluginKindOrder(it.kind) },
                    { resolution.sortOrders[it.data] ?: it.sortOrder },
                    { resolution.positions[it.data] ?: Int.MAX_VALUE },
                    { it.data.filePath },
                    { it.data.line },
                    { it.method.name }
                )
            )
        }
        return matches to resolution
    }

    private fun findPluginDeclarations(
        project: Project,
        targetFqns: Set<String>
    ): Map<String, List<PluginDeclarationRecord>> {
        val declarationsByTarget = linkedMapOf<String, MutableList<PluginDeclarationRecord>>()
        val fileBasedIndex = FileBasedIndex.getInstance()
        val psiManager = PsiManager.getInstance(project)
        val searchScope = GlobalSearchScope.allScope(project)

        for (targetFqn in targetFqns) {
            ProgressManager.checkCanceled()

            val virtualFiles = fileBasedIndex.getContainingFiles(
                com.magento.idea.magento2plugin.stubs.indexes.PluginIndex.KEY,
                targetFqn,
                searchScope
            )
            for (virtualFile in virtualFiles) {
                ProgressManager.checkCanceled()

                val xmlFile = psiManager.findFile(virtualFile) as? XmlFile ?: continue
                val filePath = MagentoMcpSupport.relativePath(project, virtualFile)
                val scope = MagentoMcpSupport.determineConfigScope(filePath, ModuleDiXml.FILE_NAME)
                collectPluginDeclarationsForTarget(
                    xmlFile = xmlFile,
                    targetFqn = targetFqn,
                    filePath = filePath,
                    scope = scope,
                    declarations = declarationsByTarget
                )
            }
        }

        return declarationsByTarget.mapValues { (_, records) ->
            records
                .distinct()
                .sortedWith(compareBy<PluginDeclarationRecord>({ it.scope }, { it.sortOrder }, { it.filePath }))
        }
    }

    private fun collectPluginDeclarationsForTarget(
        xmlFile: XmlFile,
        targetFqn: String,
        filePath: String,
        scope: String,
        declarations: MutableMap<String, MutableList<PluginDeclarationRecord>>
    ) {
        val rootTag = xmlFile.rootTag ?: return
        val lines = MagentoMcpSupport.LineIndex(xmlFile.viewProvider.contents)
        for (typeTag in rootTag.findSubTags(ModuleDiXml.TYPE_TAG)) {
            val typeName = MagentoMcpSupport.presentableFqn(typeTag.getAttributeValue(ModuleDiXml.NAME_ATTR)) ?: continue
            if (typeName != targetFqn) {
                continue
            }

            for (pluginTag in typeTag.findSubTags(ModuleDiXml.PLUGIN_TAG_NAME)) {
                declarations.getOrPut(typeName, ::mutableListOf) +=
                    PluginDeclarationRecord.of(pluginTag, filePath, lines.lineOf(pluginTag), typeName, scope)
            }
        }
    }
}
