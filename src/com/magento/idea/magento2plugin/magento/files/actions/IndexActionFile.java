/*
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */

package com.magento.idea.magento2plugin.magento.files.actions;

import com.intellij.lang.Language;
import com.jetbrains.php.lang.PhpLanguage;
import com.magento.idea.magento2plugin.actions.generation.generator.util.NamespaceBuilder;
import com.magento.idea.magento2plugin.magento.files.ModuleFileInterface;
import org.jetbrains.annotations.NotNull;

public final class IndexActionFile implements ModuleFileInterface {

    public static final String CLASS_NAME = "Index";
    public static final String FILE_EXTENSION = "php";
    public static final String TEMPLATE = "Magento Entity Index Adminhtml Controller Class";
    private static final String DIRECTORY = "Controller/Adminhtml";
    private final String entityName;
    private NamespaceBuilder namespaceBuilder;

    /**
     * Index adminhtml controller for an entity.
     *
     * @param entityName String
     */
    public IndexActionFile(
            final @NotNull String entityName
    ) {
        this.entityName = entityName;
    }

    /**
     * Get namespace builder for file.
     *
     * @param moduleName String
     *
     * @return String
     */
    public @NotNull NamespaceBuilder getNamespaceBuilder(
            final @NotNull String moduleName
    ) {
        if (namespaceBuilder == null) {
            namespaceBuilder = new NamespaceBuilder(
                    moduleName,
                    CLASS_NAME,
                    getDirectory()
            );
        }

        return namespaceBuilder;
    }

    /**
     * Get Directory path from the module root.
     *
     * @return String
     */
    public String getDirectory() {
        return DIRECTORY.concat("/" + entityName);
    }

    @Override
    public String getFileName() {
        return CLASS_NAME.concat("." + FILE_EXTENSION);
    }

    @Override
    public String getTemplate() {
        return TEMPLATE;
    }

    @Override
    public Language getLanguage() {
        return PhpLanguage.INSTANCE;
    }
}