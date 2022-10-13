/*
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */

package com.magento.idea.magento2plugin.actions.generation.data;

public class ViewModelFileData {
    private final String viewModelDirectory;
    private final String viewModelClassName;
    private final String viewModelModule;
    private final String viewModelXmlArgumentName;
    private final String namespace;

    /**
     * View model file data constructor.
     *
     * @param viewModelDirectory String
     * @param viewModelClassName String
     * @param viewModelModule String
     * @param namespace String
     */
    public ViewModelFileData(
            final String viewModelDirectory,
            final String viewModelClassName,
            final String viewModelModule,
            final String namespace
    ) {
        this.viewModelDirectory = viewModelDirectory;
        this.viewModelClassName = viewModelClassName;
        this.viewModelModule = viewModelModule;
        this.viewModelXmlArgumentName = "";
        this.namespace = namespace;
    }

    /**
     * View model file data constructor.
     *
     * @param viewModelDirectory String
     * @param viewModelClassName String
     * @param viewModelModule String
     * @param viewModelXmlArgumentName String
     * @param namespace String
     */
    public ViewModelFileData(
            final String viewModelDirectory,
            final String viewModelClassName,
            final String viewModelModule,
            final String viewModelXmlArgumentName,
            final String namespace
    ) {
        this.viewModelDirectory = viewModelDirectory;
        this.viewModelClassName = viewModelClassName;
        this.viewModelModule = viewModelModule;
        this.viewModelXmlArgumentName = viewModelXmlArgumentName;
        this.namespace = namespace;
    }

    public String getViewModelClassName() {
        return viewModelClassName;
    }

    public String getViewModelDirectory() {
        return viewModelDirectory;
    }

    public String getViewModelModule() {
        return viewModelModule;
    }

    public String getViewModelXmlArgumentName() {
        return viewModelXmlArgumentName;
    }

    public String getNamespace() {
        return namespace;
    }
}
