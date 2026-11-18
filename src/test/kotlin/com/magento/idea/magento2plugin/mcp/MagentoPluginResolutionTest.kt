/*
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */
package com.magento.idea.magento2plugin.mcp

import com.intellij.psi.PsiDocumentManager
import org.json.JSONObject

class MagentoPluginResolutionTest : MagentoDiResolutionTestCase() {
    private fun di(path: String, body: String) = file(path, "<config>\n$body\n</config>\n")

    private fun plugin(name: String) = file("app/code/Acme/First/Plugin/$name.php",
        "<?php\nnamespace Acme\\Plugin;\n\nclass $name\n{\n    public function beforeExecute(\$subject)\n    {\n    }\n}\n")

    /**
     * `Acme\Child` extends `Acme\Base`, which implements `Acme\ActionInterface`, and implements `Acme\GetInterface`,
     * which extends it too; `Acme\Other` only extends `Acme\Base`. Each plugin class has `beforeExecute`, and its
     * name says what the merge should do with it.
     */
    private fun addDeclarations() {
        listOf("Acme_First", "Acme_Second", "Acme_Off").forEach(::module)
        file("app/code/Acme/First/Model/Classes.php", """
            <?php
            namespace Acme;

            interface ActionInterface
            {
                public function execute();
            }

            class Base implements ActionInterface
            {
                public function execute()
                {
                }
            }

            interface GetInterface extends ActionInterface
            {
            }

            class Child extends Base implements GetInterface
            {
            }

            class Other extends Base
            {
            }

            class Leaf extends Child
            {
            }

            class Holder implements ActionInterface
            {
                public function execute()
                {
                }
            }

            interface HolderGet extends ActionInterface
            {
            }

            class PickTarget extends Holder implements HolderGet
            {
            }
        """.trimIndent())
        listOf("Store", "AreaOff", "SharedFirst", "SharedSecond", "Kept", "Dis", "Off", "Revive", "Primary", "Again", "Back", "PickHolder", "PickGet").forEach(::plugin)
        di("app/etc/di.xml", """<type name="Acme\Child"><plugin name="primary" type="Acme\Plugin\Primary" sortOrder="5"/></type>""")
        di("app/code/Acme/First/etc/di.xml", """
            <type name="Acme\ActionInterface">
                <plugin name="storeCheck" type="Acme\Plugin\Store"/>
                <plugin name="areaOff" type="Acme\Plugin\AreaOff"/>
            </type>
            <type name="Acme\Child">
                <plugin name="shared" type="Acme\Plugin\SharedFirst"/>
                <plugin name="kept" type="Acme\Plugin\Kept" sortOrder="30"/>
                <plugin name="dis" type="Acme\Plugin\Dis"/>
                <plugin name="revive" type="Acme\Plugin\Revive" disabled="true"/>
            </type>
            <type name="Acme\ActionInterface"><plugin name="again" type="Acme\Plugin\Again"/><plugin name="back" type="Acme\Plugin\Back" disabled="true"/></type>
            <type name="Acme\GetInterface"><plugin name="again" disabled="false"/></type>
            <type name="Acme\Holder"><plugin name="pick" type="Acme\Plugin\PickHolder"/></type>
            <type name="Acme\HolderGet"><plugin name="pick" type="Acme\Plugin\PickGet" disabled="true"/></type>
        """.trimIndent())
        di("app/code/Acme/Second/etc/di.xml", """
            <type name="Acme\Child">
                <plugin name="shared" type="Acme\Plugin\SharedSecond"/>
                <plugin name="dis" disabled="true"/>
                <plugin name="kept"/>
            </type>
            <type name="Acme\ActionInterface"><plugin name="back" disabled="false"/></type>
        """.trimIndent())
        di("app/code/Acme/Second/etc/adminhtml/di.xml", """<type name="Acme\Base"><plugin name="storeCheck" disabled="true"/><plugin name="again" disabled="true"/></type>""" +
            """<type name="Acme\GetInterface"><plugin name="again" sortOrder="7"/></type><type name="Acme\Leaf"><plugin name="storeCheck" disabled="true"/></type>""")
        di("app/code/Acme/Second/etc/frontend/di.xml", """<type name="Acme\Child"><plugin name="kept"/><plugin name="revive" disabled="false"/></type>""")
        di("app/code/Acme/First/etc/graphql/di.xml", """<type name="Acme\ActionInterface"><plugin name="areaOff" disabled="1"/></type>""")
        di("app/code/Acme/Off/etc/di.xml", """<type name="Acme\Child"><plugin name="off" type="Acme\Plugin\Off"/><plugin name="dis" disabled="true"/></type>""")
        // Not a file Magento reads: a fixture copy inside the module.
        di("app/code/Acme/First/Test/_files/etc/di.xml", """<type name="Acme\Child"><plugin name="fixture" type="Acme\Plugin\Off"/></type>""")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun records(area: String?, extra: String = "", className: String = "Acme\\\\Child"): JSONObject = JSONObject(MagentoInspectionCommands.query(project, "plugins_for_method",
        """{"className":"$className","methodName":"execute","format":"json","limit":100${area?.let { ",\"area\":\"$it\"" }.orEmpty()}$extra}"""))

    /** status:class, plus the line that decided it, in reply order. */
    private fun statuses(area: String, className: String = "Acme\\\\Child"): List<String> {
        val records = records(area, className = className).getJSONArray("records")
        return (0 until records.length()).map { index ->
            val record = records.getJSONObject(index)
            "${record.getString("status")}:${record.getString("type").substringAfterLast('\\')}" +
                (record.optString("statusBy").takeIf { it.isNotEmpty() }?.let { "<" + it.substringAfter("app/code/Acme/") }.orEmpty())
        }
    }

    fun testAreaMergeFollowsMagento() {
        addDeclarations()
        config("Acme_First" to 1, "Acme_Second" to 1, "Acme_Off" to 0)

        assertEquals(listOf("applies:Again", "applies:AreaOff", "applies:Back", "applies:Kept", "applies:Primary", "applies:Revive", "applies:SharedSecond", "applies:Store",
            "disabled:Dis<Second/etc/di.xml:4", "disabled_module:Off", "not_loaded:Off", "overridden:SharedFirst<Second/etc/di.xml:3"),
            statuses("frontend").sortedWith(compareBy({ !it.startsWith("applies") }, { it })))
        // Plugins that run come in Magento's order: sortOrder, then position in the merged list.
        assertEquals(listOf("applies:Store", "applies:AreaOff", "applies:Again", "applies:Back", "applies:SharedSecond", "applies:Primary", "applies:Kept"),
            statuses("global").filter { it.startsWith("applies") })
        val kept = { area: String ->
            records(area).getJSONArray("records").let { list ->
                (0 until list.length()).map(list::getJSONObject).single { it.getString("name") == "kept" && it.getString("status") == "applies" }
            }
        }
        // Within a stage a declaration without sortOrder keeps the earlier one; a later stage resets it to 0.
        assertEquals(30, kept("global").getInt("sortOrder"))
        assertEquals(0, kept("frontend").getInt("sortOrder"))
        // The record points at the declaration that names the class.
        assertEquals("global", kept("frontend").getString("area"))
        // disabled="false" in an area enables a plugin disabled globally.
        assertTrue(statuses("global").contains("disabled:Revive<First/etc/di.xml:10"))
        // A child type disables a parent's plugin in one area only.
        assertTrue(statuses("adminhtml", "Acme\\\\Other").contains("disabled:Store<Second/etc/adminhtml/di.xml:2"))
        assertTrue(statuses("global", "Acme\\\\Other").contains("applies:Store"))
        // An interface's list holds only its own declarations, so GetInterface brings back nothing that Base disables,
        // and its disabled="false" without a class is dropped with that list.
        val adminhtml = records("adminhtml")
        assertTrue(statuses("adminhtml").containsAll(listOf("disabled:Store<Second/etc/adminhtml/di.xml:2", "disabled:Again<Second/etc/adminhtml/di.xml:2")))
        assertTrue(adminhtml.getJSONObject("resolution").isNull("problem"))
        // A later disabled="false" in the same stage enables it again.
        assertTrue(statuses("global").contains("applies:Back"))
        // A child that disables the plugin itself decides it in every build order, and the problem no longer names it.
        val leaf = records("adminhtml", className = "Acme\\\\Leaf")
        assertTrue(statuses("adminhtml", "Acme\\\\Leaf").contains("disabled:Store<Second/etc/adminhtml/di.xml:2"))
        assertTrue(leaf.getJSONObject("resolution").isNull("problem"))
        // When the build order decides between two classes, both are candidates.
        assertTrue(statuses("global", "Acme\\\\PickTarget").containsAll(listOf("candidate:PickHolder", "candidate:PickGet")))
        assertContains(records("global", className = "Acme\\\\PickTarget").getJSONObject("resolution").getString("problem"), "Whether pick run depends on the order")
        // disabled="1" is true for Magento.
        assertTrue(statuses("graphql").contains("disabled:AreaOff<First/etc/graphql/di.xml:2"))

        config("Acme_Off" to 1, "Acme_Second" to 1, "Acme_First" to 1)
        val reversed = statuses("global")
        assertTrue(reversed.toString(), reversed.contains("applies:SharedFirst"))
        assertTrue(reversed.toString(), reversed.contains("overridden:SharedSecond<First/etc/di.xml:7"))
        // An earlier disabled="true" survives a later declaration that does not set disabled.
        assertTrue(reversed.toString(), reversed.contains("disabled:Dis<Second/etc/di.xml:4"))
        assertTrue(reversed.toString(), reversed.contains("applies:Off"))
    }

    fun testUnknownModuleOrderLeavesCompetingNamesUndecided() {
        addDeclarations()

        val reply = records("global")
        assertContains(reply.getJSONObject("resolution").getString("problem"), "plugins named back, shared in the same stage")
        val statuses = statuses("global")
        // Modules that set different attributes, or the same value, merge the same way in any order: Dis is disabled either way.
        assertTrue(statuses.toString(), statuses.containsAll(listOf("candidate:SharedFirst", "candidate:SharedSecond", "disabled:Dis<Second/etc/di.xml:4",
            "applies:Store", "applies:Kept", "applies:Primary")))
    }

    /**
     * `Acme\Bottom` extends `Acme\Middle`, which extends `Acme\Grand`, and implements `Acme\Side`; `Acme\SideChild`
     * extends the interface `Acme\Side`, and `Acme\TieChild` extends `Acme\Grand`.
     */
    fun testParentListsFollowMagentoBuilds() {
        listOf("Acme_Third", "Acme_Fourth").forEach(::module)
        file("app/code/Acme/Third/Model/Classes.php", """
            <?php
            namespace Acme;

            class Grand
            {
                public function execute()
                {
                }
            }

            class Middle extends Grand
            {
            }

            interface Side
            {
                public function execute();
            }

            interface SideChild extends Side
            {
            }

            class Bottom extends Middle implements Side
            {
            }

            class TieChild extends Grand
            {
            }
        """.trimIndent())
        listOf("A", "B", "U", "S", "X", "Y", "Q").forEach(::plugin)
        di("app/code/Acme/Third/etc/di.xml", """
            <type name="Acme\Grand">
                <plugin name="n" type="Acme\Plugin\A" disabled="true"/>
                <plugin name="x" type="Acme\Plugin\X" sortOrder="20"/>
                <plugin name="y" type="Acme\Plugin\Y" sortOrder="10"/>
                <plugin name="u" type="Acme\Plugin\U"/>
                <plugin name="q" type="Acme\Plugin\Q" disabled="true"/>
            </type>
            <type name="Acme\Middle"><plugin name="n" disabled="false"/><plugin name="q" sortOrder="5"/></type>
            <type name="Acme\Side"><plugin name="n" type="Acme\Plugin\B" disabled="true"/><plugin name="s" type="Acme\Plugin\S"/></type>
            <type name="Acme\Bottom"><plugin name="u" disabled="true"/></type>
        """.trimIndent())
        di("app/code/Acme/Fourth/etc/di.xml", """<type name="Acme\Bottom"><plugin name="u" disabled="false"/></type>""")
        di("app/code/Acme/Third/etc/frontend/di.xml", """<type name="Acme\TieChild"><plugin name="x"/><plugin name="y"/></type>""")
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        // A child type whose modules disagree decides a plugin declared on a parent too.
        val unknown = records("global", className = "Acme\\\\Bottom")
        assertContains(unknown.getJSONObject("resolution").getString("problem"), "plugins named u in the same stage")
        assertTrue(statuses("global", "Acme\\\\Bottom").contains("candidate:U"))

        config("Acme_Third" to 1, "Acme_Fourth" to 1)
        val bottom = statuses("global", "Acme\\\\Bottom")
        assertTrue(bottom.toString(), bottom.contains("applies:U"))
        // Middle changes only the sortOrder of q, so the disabled state from Grand stays in its list.
        assertTrue(bottom.toString(), bottom.contains("disabled:Q<Third/etc/di.xml:7"))
        // With Grand built before Middle, and Side first built for Bottom, A runs; with every list cached, nothing runs.
        assertTrue(bottom.toString(), bottom.containsAll(listOf("candidate:A", "candidate:B")))
        assertContains(records("global", className = "Acme\\\\Bottom").getJSONObject("resolution").getString("problem"), "Whether n run depends")
        // Grand's list is sorted (u, y, x) before TieChild merges it, so y stays before x when both sortOrders become 0.
        assertEquals(listOf("applies:U", "applies:Y", "applies:X"), statuses("frontend", "Acme\\\\TieChild").filter { it.startsWith("applies") })
        // An interface does not inherit the plugins of the interfaces it extends.
        assertFalse(statuses("global", "Acme\\\\SideChild").contains("applies:S"))
        assertTrue(statuses("global", "Acme\\\\Side").contains("applies:S"))
    }

    fun testTextAndFilters() {
        addDeclarations()
        config("Acme_First" to 1, "Acme_Second" to 1, "Acme_Off" to 0)

        val text = MagentoInspectionCommands.query(project, "plugins_for_method", """{"className":"Acme\\Child","methodName":"execute","area":"adminhtml"}""")
        assertTrue(text, text.startsWith("Plugins Magento runs for Acme\\Child::execute() in area adminhtml: 5 method(s)."))
        assertContains(text, "- before, sortOrder 30: Acme\\Plugin\\Kept::beforeExecute() as `kept` (${root}app/code/Acme/First/etc/di.xml:8, global)")
        assertFalse(text, text.contains("Undecided"))
        assertContains(text, "Declared for global or adminhtml but not run: 7.")
        assertContains(text, "- disabled: Acme\\Plugin\\Dis::beforeExecute() as `dis` (${root}app/code/Acme/First/etc/di.xml:9, global); disabled by ${root}app/code/Acme/Second/etc/di.xml:4")
        assertContains(text, "; replaced by ${root}app/code/Acme/Second/etc/di.xml:3")
        val pick = MagentoInspectionCommands.query(project, "plugins_for_method", """{"className":"Acme\\PickTarget","methodName":"execute","area":"global"}""")
        assertContains(pick, "Undecided, may or may not run: 2.\n- Acme\\Plugin\\PickHolder::beforeExecute() as `pick`")
        assertContains(pick, "Whether pick run depends on the order")
        val global = MagentoInspectionCommands.query(project, "plugins_for_method", """{"className":"Acme\\Child","methodName":"execute","area":"global"}""")
        assertContains(global, "Declared for global but not run: 5.")
        val declarations = MagentoInspectionCommands.query(project, "plugins_for_method", """{"className":"Acme\\Child","methodName":"execute"}""")
        assertContains(declarations, "Add `area` to get the plugins Magento runs in one area")

        assertEquals(9, records("adminhtml", ""","module":"Acme_First"""").getInt("total"))
        // Without area the listing keeps its old de-duplication by class, so the fixture copy and Off count once.
        assertEquals(9, records(null).getInt("total"))
        val invalid = records("front end")
        assertEquals("invalid_request", invalid.getString("status"))
    }
}
