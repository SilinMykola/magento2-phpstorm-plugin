/*
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */
package com.magento.idea.magento2plugin.mcp

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.magento.idea.magento2plugin.BaseProjectTestCase
import org.json.JSONObject

class MagentoPreferenceResolutionTest : BaseProjectTestCase() {
    private lateinit var root: String

    override fun setUp() {
        super.setUp()
        val bar2 = JSONObject(MagentoInspectionCommands.query(project, "module", """{"moduleName":"Foo_Bar2","format":"json"}"""))
            .getJSONArray("records").getJSONObject(0).getString("path")
        root = bar2.removeSuffix("app/code/Foo/Bar2")
    }

    private fun di(path: String, vararg preferences: Pair<String, String>) {
        myFixture.addFileToProject("$root$path", "<config>\n" + preferences.joinToString("\n") { (from, to) ->
            "    <preference for=\"$from\" type=\"$to\"/>"
        } + "\n</config>\n")
    }

    private fun config(vararg modules: Pair<String, Int>) = configText("<?php\nreturn [\n    'modules' => [\n" +
        modules.joinToString("") { (name, enabled) -> "        '$name' => $enabled,\n" } + "    ],\n];\n")

    private fun configText(text: String) {
        val existing = myFixture.findFileInTempDir("${root}app/etc/config.php")
        if (existing == null) {
            myFixture.addFileToProject("${root}app/etc/config.php", text)
        } else {
            WriteCommandAction.runWriteCommandAction(project) {
                PsiDocumentManager.getInstance(project).getDocument(psiManager.findFile(existing)!!)!!.setText(text)
            }
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun module(name: String) {
        val directory = "${root}app/code/Acme/${name.substringAfter('_')}"
        myFixture.addFileToProject("$directory/registration.php", "<?php \\Magento\\Framework\\Component\\ComponentRegistrar::register(" +
            "\\Magento\\Framework\\Component\\ComponentRegistrar::MODULE, '$name', __DIR__);")
        myFixture.addFileToProject("$directory/etc/module.xml", "<config><module name=\"$name\"/></config>")
    }

    private fun addDeclarations() {
        listOf("Acme_First", "Acme_Second", "Acme_Off").forEach(::module)
        di("app/etc/di.xml", "Acme\\Api" to "Acme\\Primary", "Acme\\Solo" to "Acme\\SoloPrimary")
        di("vendor/magento/magento2-base/app/etc/di.xml", "Acme\\Api" to "Acme\\Base", "Acme\\Solo" to "Acme\\SoloBase")
        di("app/code/Acme/First/etc/di.xml", "Acme\\Api" to "Acme\\FromBar", "Acme\\Chain" to "Acme\\Middle", "Acme\\Loop" to "Acme\\LoopBack")
        di("app/code/Acme/First/etc/frontend/di.xml", "Acme\\Api" to "Acme\\FrontBar")
        di("app/code/Acme/Second/etc/di.xml", "Acme\\Api" to "Acme\\FromBar2", "Acme\\Middle" to "Acme\\End", "Acme\\LoopBack" to "Acme\\Loop")
        di("app/code/Acme/Off/etc/di.xml", "Acme\\Api" to "Acme\\Disabled")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun resolve(className: String, area: String = "global") = MagentoPreferenceResolution.resolve(project, className, area)

    private fun statuses(resolution: MagentoPreferenceResolution.Resolution) =
        resolution.candidates.map { "${it.status}:${it.record.preferenceType}" }

    fun testLastLoadedModuleWinsAndAreaOverridesGlobal() {
        addDeclarations()
        config("Acme_First" to 1, "Acme_Second" to 1, "Acme_Off" to 0)

        val global = resolve("\\Acme\\Api")
        assertEquals("Acme\\FromBar2", global.effectiveType)
        assertEquals(listOf("not_loaded:Acme\\Base", "overridden:Acme\\Primary", "overridden:Acme\\FromBar",
            "applies:Acme\\FromBar2", "disabled_module:Acme\\Disabled"), statuses(global))
        assertEquals("Acme\\FrontBar", resolve("Acme\\Api", "frontend").effectiveType)
        assertEquals("Acme\\FromBar2", resolve("Acme\\Api", "adminhtml").effectiveType)

        config("Acme_Second" to 1, "Acme_First" to 1, "Acme_Off" to 1)
        assertEquals("Acme\\Disabled", resolve("Acme\\Api").effectiveType)
        config("Acme_Off" to 1, "Acme_Second" to 1, "Acme_First" to 1)
        assertEquals("Acme\\FromBar", resolve("Acme\\Api").effectiveType)
    }

    fun testChainsAreFollowedAndCyclesAreUnknown() {
        addDeclarations()
        config("Acme_First" to 1, "Acme_Second" to 1)

        val chain = resolve("Acme\\Chain")
        assertEquals("Acme\\End", chain.effectiveType)
        assertEquals(listOf("Acme\\Middle", "Acme\\End"), chain.chain)
        val loop = resolve("Acme\\Loop")
        assertNull(loop.effectiveType)
        assertTrue(loop.reason, loop.reason.contains("cycle"))
    }

    fun testWithoutConfigPhpOnlyAgreeingDeclarationsDecide() {
        addDeclarations()

        val api = resolve("Acme\\Api")
        assertNull(api.effectiveType)
        assertTrue(api.reason, api.reason.contains("was not found"))
        assertEquals(listOf("not_loaded:Acme\\Base", "candidate:Acme\\Primary", "candidate:Acme\\FromBar",
            "candidate:Acme\\Disabled", "candidate:Acme\\FromBar2"), statuses(api))
        // The vendor copy of app/etc/di.xml is never loaded, so the root file decides alone.
        val solo = resolve("Acme\\Solo")
        assertEquals("Acme\\SoloPrimary", solo.effectiveType)
        assertEquals(listOf("not_loaded:Acme\\SoloBase", "candidate:Acme\\SoloPrimary"), statuses(solo))
        // A single declaration per step decides the chain without the module order.
        assertEquals("Acme\\End", resolve("Acme\\Chain").effectiveType)
    }

    fun testConfigPhpIsReadLikePhp() {
        addDeclarations()
        configText("""
            <?php
            return array(
                'modules' => array(
                    // 'Acme_First' => 0,
                    'Acme_Second' => 0,
                    /* 'Acme_Off' => 1, */
                    'Acme_First' => 1, # first after Acme_Second
                    'Acme_Second' => 1,
                ),
                'system' => ['Acme_Off' => 1],
            );
        """.trimIndent())

        val order = MagentoPreferenceResolution.moduleOrder(project)
        assertNull(order.problem)
        assertEquals(listOf("Acme_Second", "Acme_First"), order.modules.keys.toList())
        assertEquals(setOf("Acme_Second", "Acme_First"), order.enabled)
        val api = resolve("Acme\\Api")
        assertEquals("Acme\\FromBar", api.effectiveType)
        assertEquals("not_loaded", api.candidates.single { it.module == "Acme_Off" }.status)
    }

    fun testInspectionQueryReturnsDecisionAndRecords() {
        addDeclarations()
        config("Acme_First" to 1, "Acme_Second" to 1, "Acme_Off" to 0)

        val text = MagentoInspectionCommands.query(project, "preference", """{"className":"Acme\\Api","area":"frontend"}""")
        assertTrue(text, text.startsWith("Preference for Acme\\Api in area frontend: Acme\\FrontBar"))
        assertTrue(text, text.contains("- applies: Acme\\FrontBar (${root}app/code/Acme/First/etc/frontend/di.xml:2, Acme_First, frontend)"))

        val json = JSONObject(MagentoInspectionCommands.query(project, "preference_resolution",
            """{"className":"Acme\\Api","area":"frontend","format":"json","module":"Acme_First"}"""))
        assertEquals("ok", json.getString("status"))
        assertEquals("Acme\\FrontBar", json.getJSONObject("resolution").getString("effectiveType"))
        val records = json.getJSONArray("records")
        assertEquals(2, records.length())
        assertEquals("overridden", records.getJSONObject(0).getString("status"))
        assertEquals("applies", records.getJSONObject(1).getString("status"))
        assertEquals("frontend", records.getJSONObject(1).getString("area"))
        assertEquals(2, records.getJSONObject(1).getInt("line"))

        val invalid = JSONObject(MagentoInspectionCommands.query(project, "preference_resolution",
            """{"className":"Acme\\Api","area":"front end","format":"json"}"""))
        assertEquals("invalid_request", invalid.getString("status"))
        val nullArea = JSONObject(MagentoInspectionCommands.query(project, "preference_resolution",
            """{"className":"Acme\\Api","area":null,"format":"json"}"""))
        assertEquals("invalid_request", nullArea.getString("status"))
        val none = MagentoInspectionCommands.query(project, "preference_resolution", """{"className":"Acme\\Nothing"}""")
        assertEquals("No preference is declared for Acme\\Nothing in global.", none)
    }
}
