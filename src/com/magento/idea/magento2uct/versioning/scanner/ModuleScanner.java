/*
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */

package com.magento.idea.magento2uct.versioning.scanner;

import com.intellij.json.psi.JsonFile;
import com.intellij.json.psi.JsonObject;
import com.intellij.json.psi.JsonProperty;
import com.intellij.json.psi.JsonValue;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.magento.idea.magento2plugin.magento.files.ComposerJson;
import com.magento.idea.magento2plugin.magento.files.ModuleXml;
import com.magento.idea.magento2plugin.magento.packages.Package;
import com.magento.idea.magento2uct.versioning.scanner.data.ComponentData;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public final class ModuleScanner implements Iterable<ComponentData> {

    private static final String FRAMEWORK_LIBRARY_NAME = "magento/framework";

    private final PsiDirectory rootDirectory;
    private final List<ComponentData> componentDataList;

    /**
     * Magento 2 module components scanner constructor.
     *
     * @param rootDirectory PsiDirectory
     */
    public ModuleScanner(
            final @NotNull PsiDirectory rootDirectory
    ) {
        this.rootDirectory = rootDirectory;
        componentDataList = new ArrayList<>();
    }

    @Override
    public @NotNull Iterator<ComponentData> iterator() {
        return run().iterator();
    }

    /**
     * Run scanner.
     *
     * @return List[ComponentData]
     */
    private List<ComponentData> run() {
        componentDataList.clear();
        findModuleComponent(rootDirectory);

        return componentDataList;
    }

    /**
     * Look up magento 2 module components.
     *
     * @param directory PsiDirectory
     */
    private void findModuleComponent(final @NotNull PsiDirectory directory) {
        String name = null;
        String composerBasedName = null;
        String composerType = null;
        String type = "magento2-module";

        for (final PsiDirectory subDirectory : directory.getSubdirectories()) {
            if (Package.moduleBaseAreaDir.equals(subDirectory.getName())) {
                for (final PsiFile file : subDirectory.getFiles()) {
                    if (file instanceof XmlFile && ModuleXml.FILE_NAME.equals(file.getName())) {
                        final XmlTag rootTag = ((XmlFile) file).getRootTag();

                        if (rootTag != null && rootTag.getSubTags().length > 0) {
                            final XmlTag moduleTag = rootTag.getSubTags()[0];
                            name = moduleTag.getAttributeValue("name");
                        }
                        break;
                    }
                }
                break;
            }
        }

        for (final PsiFile file : directory.getFiles()) {
            if (file instanceof JsonFile && ComposerJson.FILE_NAME.equals(file.getName())) {
                final Pair<String, String> meta = scanModuleComposerMeta((JsonFile) file);
                composerBasedName = meta.getFirst();
                composerType = meta.getSecond();

                if (name == null && FRAMEWORK_LIBRARY_NAME.equals(composerBasedName)) {
                    name = meta.getFirst();
                    type = composerType;
                }

                if (!type.equals(composerType)) {
                    return;
                }
                break;
            }
        }

        if (name != null) {
            componentDataList.add(
                    new ComponentData(
                            name,
                            composerBasedName,
                            type,
                            directory
                    )
            );
            return;
        }

        for (final PsiDirectory subDirectory : directory.getSubdirectories()) {
            findModuleComponent(subDirectory);
        }
    }

    /**
     * Scan module metadata from the composer.json file.
     *
     * @param composerFile JsonFile
     *
     * @return Pair[String, String]
     */
    private Pair<String, String> scanModuleComposerMeta(final JsonFile composerFile) {
        final JsonValue topNode = composerFile.getTopLevelValue();
        String name = null;
        String type = null;

        if (topNode instanceof JsonObject) {
            for (final JsonProperty property : ((JsonObject) topNode).getPropertyList()) {
                switch (property.getName()) {
                    case "name":
                        if (property.getValue() != null) {
                            name = property.getValue().getText().replace("\"", "");
                        }
                        break;
                    case "type":
                        if (property.getValue() != null) {
                            type = property.getValue().getText().replace("\"", "");
                        }
                        break;
                    default:
                        break;
                }
                if (name != null && type != null) {
                    break;
                }
            }
        }

        return new Pair<>(name, type);
    }
}