/*
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */
package com.magento.idea.magento2plugin.mcp

import com.intellij.psi.PsiDocumentManager
import org.json.JSONObject

class MagentoPreferenceResolutionTest : MagentoDiResolutionTestCase() {
    private fun di(path: String, vararg preferences: Pair<String, String>) {
        file(path, "<config>\n" + preferences.joinToString("\n") { (from, to) ->
            "    <preference for=\"$from\" type=\"$to\"/>"
        } + "\n</config>\n")
    }

    private fun addDeclarations() {
        listOf("Acme_First", "Acme_Second", "Acme_Off").forEach(::module)
        di("app/etc/di.xml", "Acme\\Api" to "Acme\\Primary", "Acme\\Solo" to "Acme\\SoloPrimary")
        di("vendor/magento/magento2-base/app/etc/di.xml", "Acme\\Api" to "Acme\\Base", "Acme\\Solo" to "Acme\\SoloBase")
        di("app/code/Acme/First/etc/di.xml", "Acme\\Api" to "Acme\\FromBar", "Acme\\Chain" to "Acme\\Middle", "Acme\\Loop" to "Acme\\LoopBack")
        di("app/code/Acme/First/etc/frontend/di.xml", "Acme\\Api" to "Acme\\FrontBar")
        di("app/code/Acme/Second/etc/di.xml", "Acme\\Api" to "Acme\\FromBar2", "Acme\\Middle" to "Acme\\End", "Acme\\LoopBack" to "Acme\\Loop")
        di("app/code/Acme/Off/etc/di.xml", "Acme\\Api" to "Acme\\Disabled")
        // Magento never reads etc/global/di.xml.
        di("app/code/Acme/Second/etc/global/di.xml", "Acme\\Api" to "Acme\\GlobalDir")
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
            "applies:Acme\\FromBar2", "not_loaded:Acme\\GlobalDir", "disabled_module:Acme\\Disabled"), statuses(global))
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
            "candidate:Acme\\Disabled", "candidate:Acme\\FromBar2", "not_loaded:Acme\\GlobalDir"), statuses(api))
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
