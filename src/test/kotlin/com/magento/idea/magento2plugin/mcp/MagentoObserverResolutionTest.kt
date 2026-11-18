/*
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */
package com.magento.idea.magento2plugin.mcp

import com.intellij.psi.PsiDocumentManager
import org.json.JSONObject

class MagentoObserverResolutionTest : MagentoDiResolutionTestCase() {
    private fun events(path: String, vararg lines: String) = file(path, "<config>\n${lines.joinToString("\n")}\n</config>\n")

    /** Each observer name says what the merge should do with it; the comments give the expected outcome. */
    private fun addDeclarations() {
        listOf("Acme_First", "Acme_Second", "Acme_Off").forEach(::module)
        events("app/code/Acme/First/etc/events.xml",
            """<event name="acme_save">""",
            // Disabled by Second in either module order: the later declaration does not set disabled.
            """<observer name="inventory" instance="Acme\Observer\Inventory"/>""",
            // Replaced by Second's class.
            """<observer name="shared" instance="Acme\Observer\SharedFirst"/>""",
            // Disabled globally; the frontend disabled="false" does not enable it.
            """<observer name="revive" instance="Acme\Observer\Revive" disabled="true"/>""",
            // Disabled here and enabled by Second in the same stage.
            """<observer name="back" instance="Acme\Observer\Back" disabled="true"/>""",
            // Only `true` disables.
            """<observer name="one" instance="Acme\Observer\One" disabled="1"/>""",
            // Disabled in adminhtml only.
            """<observer name="areaOff" instance="Acme\Observer\AreaOff"/>""",
            "</event>")
        events("app/code/Acme/Second/etc/events.xml",
            """<event name="acme_save">""",
            """<observer name="inventory" disabled="true"/>""",
            """<observer name="shared" instance="Acme\Observer\SharedSecond"/>""",
            """<observer name="back" disabled="false"/>""",
            "</event>")
        events("app/code/Acme/First/etc/frontend/events.xml",
            """<event name="acme_save"><observer name="revive" disabled="false"/><observer name="front" instance="Acme\Observer\Front"/></event>""")
        events("app/code/Acme/First/etc/adminhtml/events.xml",
            """<event name="acme_save"><observer name="areaOff" disabled="true"/></event>""")
        events("app/code/Acme/Off/etc/events.xml", """<event name="acme_save"><observer name="off" instance="Acme\Observer\Off"/></event>""")
        // Not a file Magento reads: a fixture copy inside the module.
        events("app/code/Acme/First/Test/_files/etc/events.xml", """<event name="acme_save"><observer name="fixture" instance="Acme\Observer\Off"/></event>""")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun records(area: String?, event: String = "acme_save", extra: String = ""): JSONObject = JSONObject(MagentoInspectionCommands.query(project, "observers_for_event",
        """{"eventName":"$event","format":"json","limit":100${area?.let { ",\"area\":\"$it\"" }.orEmpty()}$extra}"""))

    /** status:name:class, plus the line that decided it, in reply order. */
    private fun statuses(area: String, event: String = "acme_save"): List<String> {
        val records = records(area, event).getJSONArray("records")
        return (0 until records.length()).map { index ->
            val record = records.getJSONObject(index)
            "${record.getString("status")}:${record.getString("observerName")}:${record.getString("instance").substringAfterLast('\\')}" +
                (record.optString("statusBy").takeIf { it.isNotEmpty() }?.let { "<" + it.substringAfter("app/code/Acme/") }.orEmpty())
        }
    }

    fun testAreaMergeFollowsMagento() {
        addDeclarations()
        config("Acme_First" to 1, "Acme_Second" to 1, "Acme_Off" to 0)

        assertEquals(listOf(
            "not_loaded:fixture:Off",
            "disabled:inventory:Inventory<Second/etc/events.xml:3",
            "overridden:shared:SharedFirst<Second/etc/events.xml:4",
            "disabled:revive:Revive<First/etc/events.xml:5",
            "applies:back:Back",
            "applies:one:One",
            "applies:areaOff:AreaOff",
            "merged:inventory:-",
            "applies:shared:SharedSecond",
            "merged:back:-",
            "disabled_module:off:Off",
            // Declarations on one line come in name order.
            "applies:front:Front",
            "merged:revive:-"
        ), statuses("frontend"))
        assertTrue(statuses("adminhtml").contains("disabled:areaOff:AreaOff<First/etc/adminhtml/events.xml:2"))
        // Only global and the requested area are listed, and other areas do not change the result.
        assertFalse(statuses("global").any { "front" in it || "revive:-" in it })
        assertTrue(statuses("global").contains("applies:areaOff:AreaOff"))
        val reply = records("frontend")
        assertTrue(reply.getJSONObject("resolution").isNull("problem"))
        assertEquals("frontend", reply.getJSONObject("resolution").getString("area"))
        assertContains(reply.getJSONObject("resolution").getString("moduleOrderSource"), "app/etc/config.php")

        config("Acme_Off" to 1, "Acme_Second" to 1, "Acme_First" to 1)
        val reversed = statuses("global")
        assertTrue(reversed.toString(), reversed.contains("applies:shared:SharedFirst"))
        assertTrue(reversed.toString(), reversed.contains("overridden:shared:SharedSecond<First/etc/events.xml:4"))
        // An earlier disabled="true" survives a later declaration that does not set disabled.
        assertTrue(reversed.toString(), reversed.contains("disabled:inventory:Inventory<Second/etc/events.xml:3"))
        // A disabled="true" after disabled="false" in the same stage disables it.
        assertTrue(reversed.toString(), reversed.contains("disabled:back:Back<First/etc/events.xml:6"))
        assertTrue(reversed.toString(), reversed.contains("applies:off:Off"))
    }

    fun testUnknownModuleOrderLeavesCompetingNamesUndecided() {
        addDeclarations()

        val reply = records("global")
        assertContains(reply.getJSONObject("resolution").getString("problem"), "acme_save: Several modules declare observers named back, shared for this event")
        val statuses = statuses("global")
        // inventory is disabled in either order, so it is decided.
        assertTrue(statuses.toString(), statuses.containsAll(listOf("candidate:shared:SharedFirst", "candidate:shared:SharedSecond", "candidate:back:Back",
            "disabled:inventory:Inventory<Second/etc/events.xml:3", "applies:one:One")))
    }

    fun testEventNamesDifferingByCase() {
        listOf("Acme_First", "Acme_Second").forEach(::module)
        events("app/code/Acme/First/etc/events.xml",
            """<event name="Acme_Case"><observer name="first" instance="Acme\Observer\First"/></event>""")
        events("app/code/Acme/Second/etc/events.xml",
            """<event name="acme_case"><observer name="second" instance="Acme\Observer\Second"/></event>""")
        events("app/code/Acme/Second/etc/frontend/events.xml",
            """<event name="ACME_CASE"><observer name="front" instance="Acme\Observer\Front"/></event>""")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        config("Acme_First" to 1, "Acme_Second" to 1)

        // In one stage the later event replaces the earlier one; the area stage merges into the result.
        assertEquals(listOf("overridden:first:First<Second/etc/events.xml:2", "applies:second:Second", "applies:front:Front"),
            statuses("frontend", "acme_case"))
        val records = records("frontend", "ACME_case").getJSONArray("records")
        assertEquals(3, records.length())
        assertEquals("acme_case", records.getJSONObject(0).getString("key"))
    }

    fun testModuleAtProjectRootIsLoaded() {
        module("Acme_First")
        events("app/code/Acme/First/etc/events.xml", """<event name="acme_root"><observer name="shared" instance="Acme\Observer\First"/></event>""")
        myFixture.addFileToProject("etc/module.xml", "<config><module name=\"Acme_Root\"/></config>")
        myFixture.addFileToProject("etc/events.xml",
            """<config><event name="acme_root"><observer name="shared" instance="Acme\Observer\Root"/></event></config>""")
        myFixture.addFileToProject("etc/frontend/events.xml",
            """<config><event name="acme_root"><observer name="shared" disabled="true"/></event></config>""")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        config("Acme_First" to 1, "Acme_Root" to 1)

        assertEquals(listOf("overridden:shared:First<etc/events.xml:1", "applies:shared:Root"), statuses("global", "acme_root"))
        assertEquals(listOf("overridden:shared:First<etc/events.xml:1", "disabled:shared:Root<etc/frontend/events.xml:1", "merged:shared:-"),
            statuses("frontend", "acme_root"))
    }

    fun testTextAndFilters() {
        addDeclarations()
        config("Acme_First" to 1, "Acme_Second" to 1, "Acme_Off" to 0)

        val text = MagentoInspectionCommands.query(project, "observers_for_event", """{"eventName":"acme_save","area":"adminhtml"}""")
        assertContains(text, "acme_save: Magento runs 3 observer(s) in area adminhtml.")
        assertContains(text, "- `shared` Acme\\Observer\\SharedSecond (${root}app/code/Acme/Second/etc/events.xml:4)")
        assertContains(text, "Declared for global or adminhtml but not run:")
        assertContains(text, "- disabled: `areaOff` Acme\\Observer\\AreaOff (${root}app/code/Acme/First/etc/events.xml:8); " +
            "disabled by ${root}app/code/Acme/First/etc/adminhtml/events.xml:2")
        assertContains(text, "- overridden: `shared` Acme\\Observer\\SharedFirst (${root}app/code/Acme/First/etc/events.xml:4); " +
            "replaced by ${root}app/code/Acme/Second/etc/events.xml:4")
        assertContains(text, "Module order: ${root}app/etc/config.php.")
        assertFalse(text, text.contains("Front"))
        // Without area the reply lists every declaration and says that it is not the merged result.
        val all = MagentoInspectionCommands.query(project, "observers_for_event", """{"eventName":"acme_save"}""")
        assertContains(all, "observer=front")
        assertContains(all, "Add `area` to get the observers Magento runs in one area")
        // With area, area selects the merge instead of filtering by file area; module and path still filter.
        val filtered = records("frontend", extra = ""","module":"Acme_First","path":"${root}app/code/Acme/First/etc"""").getJSONArray("records")
        assertEquals(listOf("inventory", "shared", "revive", "back", "one", "areaOff", "front", "revive"),
            (0 until filtered.length()).map { filtered.getJSONObject(it).getString("observerName") })
        assertEquals(14, records(null).getInt("total"))
        assertContains(MagentoInspectionCommands.query(project, "observers_for_event", """{"eventName":"acme_save","area":"no area"}"""),
            "area must be an area code")
        assertEquals("invalid_request", records("no area").getString("status"))
    }
}
