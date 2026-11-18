/*
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */
package com.magento.idea.magento2plugin.mcp

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.magento.idea.magento2plugin.BaseProjectTestCase
import org.json.JSONObject

/** A Magento root in the test project with Acme modules, their di.xml files and app/etc/config.php. */
abstract class MagentoDiResolutionTestCase : BaseProjectTestCase() {
    protected lateinit var root: String

    override fun setUp() {
        super.setUp()
        val bar2 = JSONObject(MagentoInspectionCommands.query(project, "module", """{"moduleName":"Foo_Bar2","format":"json"}"""))
            .getJSONArray("records").getJSONObject(0).getString("path")
        root = bar2.removeSuffix("app/code/Foo/Bar2")
    }

    protected fun file(path: String, text: String) {
        myFixture.addFileToProject("$root$path", text)
    }

    protected fun config(vararg modules: Pair<String, Int>) = configText("<?php\nreturn [\n    'modules' => [\n" +
        modules.joinToString("") { (name, enabled) -> "        '$name' => $enabled,\n" } + "    ],\n];\n")

    protected fun configText(text: String) {
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

    protected fun module(name: String) {
        val directory = "app/code/Acme/${name.substringAfter('_')}"
        file("$directory/registration.php", "<?php \\Magento\\Framework\\Component\\ComponentRegistrar::register(" +
            "\\Magento\\Framework\\Component\\ComponentRegistrar::MODULE, '$name', __DIR__);")
        file("$directory/etc/module.xml", "<config><module name=\"$name\"/></config>")
    }

    protected fun assertContains(text: String, expected: String) {
        assertTrue("Expected <$expected> in:\n$text", text.contains(expected))
    }
}
