/*
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */

package com.magento.idea.magento2plugin.actions.generation.generator; //NOPMD

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.maddyhome.idea.copyright.actions.UpdateCopyrightProcessor;
import com.magento.idea.magento2plugin.actions.generation.InjectAViewModelAction;
import com.magento.idea.magento2plugin.actions.generation.data.ViewModelFileData;
import com.magento.idea.magento2plugin.actions.generation.generator.code.ClassArgumentInXmlConfigGenerator;
import com.magento.idea.magento2plugin.actions.generation.generator.util.DirectoryGenerator;
import com.magento.idea.magento2plugin.actions.generation.generator.util.FindOrCreateLayoutXml;
import com.magento.idea.magento2plugin.actions.generation.generator.util.NamespaceBuilder;
import com.magento.idea.magento2plugin.bundles.ValidatorBundle;
import com.magento.idea.magento2plugin.indexes.ModuleIndex;
import com.magento.idea.magento2plugin.magento.files.LayoutXml;
import com.magento.idea.magento2plugin.magento.packages.Areas;
import com.magento.idea.magento2plugin.magento.packages.ComponentType;
import com.magento.idea.magento2plugin.magento.packages.File;
import com.magento.idea.magento2plugin.magento.packages.Package;
import com.magento.idea.magento2plugin.magento.packages.XsiTypes;
import com.magento.idea.magento2plugin.util.magento.GetMagentoModuleUtil;
import com.magento.idea.magento2plugin.util.magento.area.AreaResolverUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

public class OverrideTemplateInModuleGenerator {

    private final Project project;
    private final ValidatorBundle validatorBundle;
    private final FindOrCreateLayoutXml findOrCreateLayoutXml;

    /**
     * OverrideInThemeGenerator constructor.
     *
     * @param project Project
     */
    public OverrideTemplateInModuleGenerator(final Project project) {
        this.project = project;
        this.validatorBundle = new ValidatorBundle();
        this.findOrCreateLayoutXml = new FindOrCreateLayoutXml(project);
    }

    /**
     * Action entry point.
     *
     * @param baseFile PsiFile
     * @param moduleName String
     */
    public void execute(
            final @NotNull PsiFile baseFile,
            final String moduleName,
            final String blockName,
            final @NotNull VirtualFile layoutFile,
            final boolean addViewModel,
            final ViewModelFileData viewModelFileData
            ) {
        if (AreaResolverUtil.getForFileInModule(layoutFile) == null) {
            return;
        }
        final String[] layoutNameParts = getLayoutNameParts(layoutFile);
        final XmlFile layout = (XmlFile) this.findOrCreateLayoutXml.execute(
                layoutNameParts[2],
                layoutNameParts[0],
                layoutNameParts[1],
                layoutNameParts[2],
                moduleName,
                AreaResolverUtil.getForFileInModule(layoutFile).toString()
        );

        if (layout == null) {
            return;
        }

        final GetMagentoModuleUtil.MagentoModuleData moduleData =
                GetMagentoModuleUtil.getByContext(baseFile.getContainingDirectory(), project);

        if (moduleData == null || !moduleData.getType().equals(ComponentType.module)) {
            return;
        }
        final ModuleIndex moduleIndex = new ModuleIndex(project);
        PsiDirectory directory = moduleIndex.getModuleDirectoryByModuleName(moduleName);

        if (directory == null) {
            return;
        }
        final List<String> pathComponents = getModulePathComponents(baseFile);
        directory = getTargetDirectory(directory, pathComponents);
        final String templatePath = getTemplateXmlPath(
                pathComponents,
                moduleName,
                baseFile.getName()
        );
        writeChangesToLayoutFile(layout, blockName, templatePath);

        if (addViewModel) {
            createViewModel(layout, viewModelFileData, moduleName, blockName);
        }
        final PsiFile existentFile = directory.findFile(baseFile.getName());

        if (existentFile != null) {
            JBPopupFactory.getInstance()
                    .createMessage(
                            validatorBundle.message(
                                    "validator.file.alreadyExists",
                                    baseFile.getName()
                            )
                    )
                    .showCenteredInCurrentWindow(project);
            existentFile.navigate(true);
            return;
        }
        final PsiDirectory finalDirectory = directory;

        ApplicationManager.getApplication().runWriteAction(() -> {
            finalDirectory.copyFileFrom(baseFile.getName(), baseFile);
        });
        final PsiFile newFile = finalDirectory.findFile(baseFile.getName());

        if (newFile == null) {
            JBPopupFactory.getInstance()
                    .createMessage(
                            validatorBundle.message(
                                    "validator.file.cantBeCreated",
                                    baseFile.getName()
                            )
                    )
                    .showCenteredInCurrentWindow(project);
            return;
        }
        final Module module = ModuleUtilCore.findModuleForPsiElement(newFile);
        final UpdateCopyrightProcessor processor = new UpdateCopyrightProcessor(
                project,
                module,
                newFile
        );
        processor.run();
        newFile.navigate(true);
    }

    private void createViewModel(
            final XmlFile layout,
            final ViewModelFileData viewModelFileData,
            final String moduleName,
            final String blockName
    ) {
        new ModuleViewModelClassGenerator(viewModelFileData, project)
                .generate(InjectAViewModelAction.ACTION_NAME, true);
        final XmlTag rootTag = layout.getRootTag();

        if (rootTag == null) {
            return;
        }
        final XmlTag bodyTag = rootTag.findFirstSubTag(LayoutXml.ROOT_TAG_NAME);

        if (bodyTag == null) {
            return;
        }
        final XmlTag targetTag = getTargetTag(bodyTag, blockName);
        if (targetTag == null) {
            return;
        }
        final NamespaceBuilder namespaceBuilder = new NamespaceBuilder(
                moduleName,
                viewModelFileData.getViewModelClassName(),
                viewModelFileData.getViewModelDirectory()
        );
        new ClassArgumentInXmlConfigGenerator(
                project,
                viewModelFileData.getViewModelXmlArgumentName(),
                XsiTypes.object.toString(),
                namespaceBuilder.getClassFqn()
        ).generate(targetTag);
    }

    private void writeChangesToLayoutFile(
            final XmlFile layout,
            final String blockName,
            final String templatePath
    ) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            final XmlTag rootTag = layout.getRootTag();
            if (rootTag == null) {
                return;
            }
            XmlTag bodyTag = rootTag.findFirstSubTag(LayoutXml.ROOT_TAG_NAME);
            boolean isBodyTagNew = false;
            if (bodyTag == null) {
                bodyTag = rootTag.createChildTag(
                        LayoutXml.ROOT_TAG_NAME,
                        null,
                        "",
                        false
                );
                isBodyTagNew = true;
            }
            final PsiDocumentManager psiDocumentManager = PsiDocumentManager.getInstance(project);
            final Document document = psiDocumentManager.getDocument(layout);

            if (document == null) {
                return;
            }
            XmlTag targetTag = getTargetTag(bodyTag, blockName);

            boolean isTargetTagNew = false;
            if (targetTag == null) {
                targetTag = bodyTag.createChildTag(
                        LayoutXml.REFERENCE_BLOCK_ATTRIBUTE_TAG_NAME,
                        null,
                        "",
                        false
                );
                isTargetTagNew = true;
            }

            if (isTargetTagNew) {
                targetTag.setAttribute(LayoutXml.NAME_ATTRIBUTE, blockName);
                targetTag.setAttribute(LayoutXml.XML_ATTRIBUTE_TEMPLATE, templatePath);
                targetTag.getValue().setText("");
                targetTag.collapseIfEmpty();
                bodyTag.addSubTag(targetTag, false);
            } else {
                targetTag.getAttribute(LayoutXml.XML_ATTRIBUTE_TEMPLATE).setValue(templatePath);
            }

            if (isBodyTagNew) {
                rootTag.addSubTag(bodyTag, false);
            }
            psiDocumentManager.commitDocument(document);
        });
    }

    private XmlTag getTargetTag(final @NotNull XmlTag rootTag, final @NotNull String blockName) {
        final Collection<XmlTag> tags = PsiTreeUtil.findChildrenOfType(rootTag, XmlTag.class);
        final List<XmlTag> result = tags.stream().filter(
                xmlTag -> xmlTag.getName().equals(LayoutXml.REFERENCE_BLOCK_ATTRIBUTE_TAG_NAME)
                && xmlTag.getAttributeValue(LayoutXml.NAME_ATTRIBUTE) != null
                && xmlTag.getAttributeValue(LayoutXml.NAME_ATTRIBUTE).equals(blockName)
        ).collect(Collectors.toList());

        return result.isEmpty() ? null : result.get(0);
    }

    private String getTemplateXmlPath(
            final List<String> pathComponents,
            final String moduleName,
            final String fileName
    ) {
        String templatePath = moduleName.concat("::");
        boolean afterTemplate = false;
        for (final String pathComponent : pathComponents) {
            if (afterTemplate) {
                templatePath = templatePath.concat(pathComponent).concat(Package.V_FILE_SEPARATOR);
                continue;
            }

            if ("templates".equals(pathComponent)) {
                afterTemplate = true;
            }

        }

        return templatePath.concat(fileName);
    }

    private List<String> getModulePathComponents(final PsiFile file) {
        final List<String> pathComponents = new ArrayList<>();
        final List<String> allowedAreas = new ArrayList<>();
        allowedAreas.add(Areas.frontend.toString());
        allowedAreas.add(Areas.adminhtml.toString());
        allowedAreas.add(Areas.base.toString());
        PsiDirectory parent = file.getParent();

        while (parent != null
                && !allowedAreas.contains(parent.getName())) {
            pathComponents.add(parent.getName());
            parent = parent.getParent();
        }

        if (parent != null && allowedAreas.contains(parent.getName())) {
            pathComponents.add(parent.getName());
        }
        pathComponents.add("view");
        Collections.reverse(pathComponents);

        return pathComponents;
    }

    private PsiDirectory getTargetDirectory(
            final PsiDirectory directory,
            final List<String> pathComponents
    ) {
        final DirectoryGenerator generator = DirectoryGenerator.getInstance();

        return generator.findOrCreateSubdirectories(
                directory,
                pathComponents.stream().collect(Collectors.joining(File.separator))
        );
    }

    private String[] getLayoutNameParts(final VirtualFile layoutFile) {

        final String[] layoutNameParts = layoutFile.getNameWithoutExtension().split("_");
        String routeName = "";
        String controllerName = "";
        String actionName = "";

        if (layoutNameParts.length >= 1) { // NOPMD
            routeName = layoutNameParts[0];
        }

        if (layoutNameParts.length == 3) { // NOPMD
            controllerName = layoutNameParts[1];
            actionName = layoutNameParts[2];
        }

        if (layoutNameParts.length == 2 || layoutNameParts.length > 3) { // NOPMD
            routeName = layoutFile.getNameWithoutExtension();
        }

        return new String[]{routeName, controllerName, actionName};
    }
}
