/**
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */
package com.magento.idea.magento2plugin.mcp

import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass

/** PHP PSI stays behind the optional inspection provider. */
internal object MagentoPhpInspectionSupport {
    fun resolveTargetClasses(phpIndex: PhpIndex, className: String): List<PhpClass> {
        val classes = phpIndex.getClassesByFQN(className).toMutableList()
        classes += phpIndex.getInterfacesByFQN(className)
        return classes.distinctBy { it.fqn }
    }

    /**
     * Direct parents as Magento's `ClassReader::getParents()` lists them for plugin inheritance: the parent
     * class, then the interfaces the parent class does not implement. An interface has none, because
     * `Relations\Runtime::getParents()` returns nothing when `class_exists()` is false.
     */
    fun magentoParents(phpIndex: PhpIndex, className: String): List<String> {
        val phpClass = resolveTargetClasses(phpIndex, className).firstOrNull { !it.isInterface } ?: return emptyList()
        val own = interfaces(phpClass)
        val parent = phpClass.superClass ?: return own.toList()
        return listOf(parent.presentableFQN) + (own - interfaces(parent))
    }

    private fun interfaces(
        phpClass: PhpClass,
        found: MutableSet<String> = linkedSetOf(),
        visited: MutableSet<String> = hashSetOf()
    ): Set<String> {
        if (!visited.add(phpClass.presentableFQN)) return found
        for (parent in phpClass.supers) {
            if (parent.isInterface) found += parent.presentableFQN
            interfaces(parent, found, visited)
        }
        return found
    }

    fun collectClassHierarchy(phpClass: PhpClass, fqns: MutableSet<String>) {
        fqns += phpClass.presentableFQN
        for (parent in phpClass.supers) {
            val parentFqn = parent.fqn.removePrefix("\\")
            if (fqns.add(parentFqn)) {
                collectClassHierarchy(parent, fqns)
            }
        }
    }

    data class PluginMethodMatch(
        val targetFqn: String,
        val data: PluginDeclarationRecord,
        val pluginClass: PhpClass,
        val method: Method,
        val kind: String
    ) {
        val sortOrder: Int
            get() = data.sortOrder

        val scope: String
            get() = data.scope
    }
}
