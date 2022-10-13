/*
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */

package com.magento.idea.magento2plugin.actions.generation.dialog; //NOPMD

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.FileBasedIndex;
import com.magento.idea.magento2plugin.actions.generation.OverrideTemplateInModuleAction;
import com.magento.idea.magento2plugin.actions.generation.data.ViewModelFileData;
import com.magento.idea.magento2plugin.actions.generation.data.ui.ComboBoxItemData;
import com.magento.idea.magento2plugin.actions.generation.dialog.validator.annotation.FieldValidation;
import com.magento.idea.magento2plugin.actions.generation.dialog.validator.annotation.RuleRegistry;
import com.magento.idea.magento2plugin.actions.generation.dialog.validator.rule.NotEmptyRule;
import com.magento.idea.magento2plugin.actions.generation.generator.OverrideTemplateInModuleGenerator;
import com.magento.idea.magento2plugin.actions.generation.generator.util.NamespaceBuilder;
import com.magento.idea.magento2plugin.indexes.ModuleIndex;
import com.magento.idea.magento2plugin.magento.files.ViewModelPhp;
import com.magento.idea.magento2plugin.magento.packages.Areas;
import com.magento.idea.magento2plugin.magento.packages.Package;
import com.magento.idea.magento2plugin.stubs.indexes.BlockNameIndex;
import com.magento.idea.magento2plugin.util.magento.GetMagentoModuleUtil;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import org.jetbrains.annotations.NotNull;

public class OverrideTemplateInModuleDialog extends AbstractDialog { //NOPMD - suppressed TooManyFields

    private static final int CHECKBOX_CHECKED_VALUE = 1;
    private static final String MODULE_NAME = "Target Module";
    private static final String BLOCK_NAME = "Target Block";
    private static final String LAYOUT_NAME = "Target Layout";
    private final @NotNull Project project;
    private final PsiFile psiFile;
    private JPanel contentPane;
    private JLabel selectModule; //NOPMD
    private JLabel selectBlock; //NOPMD
    private JLabel selectLayout;
    private JLabel viewModelCheckBoxLabel;
    private JLabel viewModelArgumentLabel;
    private JLabel viewModelClassLabel;
    private JLabel viewModelDirectoryLabel;
    private JCheckBox addViewModel;
    @FieldValidation(rule = RuleRegistry.NOT_EMPTY, message = {NotEmptyRule.MESSAGE, MODULE_NAME})
    private JComboBox module;
    @FieldValidation(rule = RuleRegistry.NOT_EMPTY, message = {NotEmptyRule.MESSAGE, BLOCK_NAME})
    private JComboBox overridingBlock;
    @FieldValidation(rule = RuleRegistry.NOT_EMPTY, message = {NotEmptyRule.MESSAGE, LAYOUT_NAME})
    private JComboBox layout;
    private JButton buttonOK;
    private JButton buttonCancel;
    private JTextField viewModelClassName;
    private JTextField viewModelDirectory;
    private JTextField viewModelArgument;
    private Collection<VirtualFile> selectedLayouts;

    /**
     * Constructor.
     *
     * @param project Project
     * @param psiFile PsiFile
     */
    public OverrideTemplateInModuleDialog(
            final @NotNull Project project,
            final @NotNull PsiFile psiFile
    ) {
        super();

        this.project = project;
        this.psiFile = psiFile;

        setContentPane(contentPane);
        setModal(true);
        setTitle(OverrideTemplateInModuleAction.ACTION_TEMPLATE_DESCRIPTION);
        getRootPane().setDefaultButton(buttonOK);
        fillModuleOptions();
        fillBlockOptions();
        viewModelDirectory.setText(ViewModelPhp.DEFAULT_DIR);

        buttonOK.addActionListener((final ActionEvent event) -> onOK());
        buttonCancel.addActionListener((final ActionEvent event) -> onCancel());

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(final WindowEvent event) {
                onCancel();
            }
        });

        contentPane.registerKeyboardAction(
                (final ActionEvent event) -> onCancel(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
        );

        addComponentListener(new FocusOnAFieldListener(() -> module.requestFocusInWindow()));

        selectLayout.setVisible(false);
        layout.setVisible(false);
        changeViewModelCheckBoxDialogStatus(false);
        changeViewModelClassDialogStatus(false);
        addListeners();
    }

    private void addListeners() { //NOPMD - suppressed CognitiveComplexity
        module.addItemListener(event -> {
            if (!((event.getItem()) instanceof ComboBoxItemData)) {
                return;
            }
            final ComboBoxItemData selectedModule = (ComboBoxItemData) event.getItem();

            if (selectedModule.getKey().length() == 0) {
                overridingBlock.removeAllItems();
                fillBlockOptions();
                selectLayout.setVisible(false);
                layout.setVisible(false);
                changeViewModelCheckBoxDialogStatus(false);
                changeViewModelClassDialogStatus(false);
                return;
            }
            overridingBlock.setVisible(true);
        });
        overridingBlock.addItemListener(event -> {
            if (!((event.getItem()) instanceof ComboBoxItemData)) {
                return;
            }
            final ComboBoxItemData selectedBlock = (ComboBoxItemData) event.getItem();

            if (selectedBlock.getKey().length() == 0) {
                selectLayout.setVisible(false);
                layout.setVisible(false);
                changeViewModelCheckBoxDialogStatus(false);
                return;
            }
            changeSelectLayoutComboBox(selectedBlock.getKey());
        });
        layout.addItemListener(event -> {
            if (!((event.getItem()) instanceof ComboBoxItemData)) {
                return;
            }
            final ComboBoxItemData selectedLayout = (ComboBoxItemData)  event.getItem();

            if (selectedLayout.getKey().length() > 0) {
                changeViewModelCheckBoxDialogStatus(true);
            } else {
                changeViewModelCheckBoxDialogStatus(false);
            }
        });
        addViewModel.addItemListener(event -> {
            if (!((event.getItem()) instanceof JCheckBox)) {
                return;
            }

            if (event.getStateChange() == CHECKBOX_CHECKED_VALUE) {
                changeViewModelClassDialogStatus(true);
            } else {
                changeViewModelClassDialogStatus(false);
            }
        });
    }

    private void changeViewModelCheckBoxDialogStatus(final boolean visibility) {
        addViewModel.setVisible(visibility);
        viewModelCheckBoxLabel.setVisible(visibility);

        if (!visibility) {
            addViewModel.setSelected(false);
        }
    }

    private void changeViewModelClassDialogStatus(final boolean visibility) {
        viewModelClassName.setVisible(visibility);
        viewModelClassLabel.setVisible(visibility);
        viewModelDirectory.setVisible(visibility);
        viewModelDirectoryLabel.setVisible(visibility);
        viewModelArgument.setVisible(visibility);
        viewModelArgumentLabel.setVisible(visibility);
    }

    private void fillBlockOptions() {
        final Collection<String> blockNames =
                FileBasedIndex.getInstance().getAllKeys(BlockNameIndex.KEY, this.project);
        overridingBlock.addItem(
                new ComboBoxItemData("", " --- Select Block --- ")
        );
        final String filePath = psiFile.getVirtualFile().getPath();
        final String fileArea = getArea(filePath);
        for (final String blockName : blockNames) {
            if (fileArea.equals(getBlockArea(blockName)) && isBlockLayoutInModule(blockName)) {
                overridingBlock.addItem(new ComboBoxItemData(blockName, blockName)); //NOPMD
            }
        }
    }

    private String getBlockArea(final String blockName) {
        String area = "";
        final List<String> areaList = getAreaList();
        final Collection<VirtualFile> virtualFiles =
                FileBasedIndex.getInstance().getContainingFiles(
                        BlockNameIndex.KEY,blockName,
                        GlobalSearchScope.allScope(project)
                );
        for (final VirtualFile virtualFile : virtualFiles) {
            for (final String filePath: virtualFile.getPath().split(Package.V_FILE_SEPARATOR)) {
                if (areaList.contains(filePath)) {
                    area = filePath;
                    break;
                }
            }
        }

        return area;
    }

    private boolean isBlockLayoutInModule(final String blockName) {
        boolean result = false;
        final Collection<VirtualFile> virtualFiles =
                FileBasedIndex.getInstance().getContainingFiles(
                        BlockNameIndex.KEY,blockName,
                        GlobalSearchScope.allScope(project)
                );
        for (final VirtualFile virtualFile : virtualFiles) {
            if (virtualFile.getPath().contains("/" + Package.moduleViewDir + "/")) {
                result = true;
            }
        }

        return result;
    }

    private void fillModuleOptions() {
        final GetMagentoModuleUtil.MagentoModuleData moduleData =
                GetMagentoModuleUtil.getByContext(psiFile.getContainingDirectory(), project);

        if (moduleData == null) {
            return;
        }
        module.addItem(
                new ComboBoxItemData("", " --- Select Module --- ")
        );
        final List<String> moduleNames = new ModuleIndex(project).getEditableModuleNames();
        for (final String moduleName : moduleNames) {
            if (!moduleData.getName().equals(moduleName)) {
                module.addItem(new ComboBoxItemData(moduleName, moduleName)); //NOPMD
            }
        }
    }

    private void fillLayoutOptions(final @NotNull Collection<VirtualFile> layoutFiles) {
        if (layoutFiles.isEmpty()) {
            return;
        }
        selectedLayouts = layoutFiles;
        layout.removeAllItems();
        layout.addItem(
                new ComboBoxItemData("", " --- Select Layout --- ")
        );
        for (final VirtualFile layoutFile : layoutFiles) {
            layout.addItem(
                    new ComboBoxItemData(layoutFile.getName(), layoutFile.getName()) //NOPMD
            );
        }
    }

    private void onOK() {
        if (validateFormFields()) {
            final OverrideTemplateInModuleGenerator overrideInModuleGenerator =
                    new OverrideTemplateInModuleGenerator(project);

            final NamespaceBuilder namespaceBuilder = new NamespaceBuilder(
                    getModuleName(),
                    getViewModelClassName(),
                    getViewModelDirectory()
            );
            overrideInModuleGenerator.execute(
                    psiFile,
                    getModuleName(),
                    getSelectedBlock(),
                    getSelectedLayoutFile(),
                    addViewModel.isSelected(),
                    new ViewModelFileData(
                            getViewModelDirectory(),
                            getViewModelClassName(),
                            getModuleName(),
                            getViewModelArgumentName(),
                            namespaceBuilder.getNamespace())
            );
            exit();
        }
    }

    private String getViewModelClassName() {
        return this.viewModelClassName.getText().trim();
    }

    private String getViewModelDirectory() {
        return this.viewModelDirectory.getText().trim();
    }

    private String getViewModelArgumentName() {
        return this.viewModelArgument.getText().trim();
    }

    private String getArea(final String filePath) {
        String area = ""; // NOPMD;
        final List<String> areaList = getAreaList();
        for (final String namePath : filePath.split(Package.V_FILE_SEPARATOR)) {
            if (areaList.contains(namePath)) {
                area = namePath;
                break;
            }
        }

        return area;
    }

    private List<String> getAreaList() {
        return new ArrayList<>(
                Arrays.asList(
                        Areas.frontend.toString(),
                        Areas.adminhtml.toString()
                )
        );
    }

    private String getModuleName() {
        return this.module.getSelectedItem().toString();
    }

    private String getSelectedBlock() {
        return this.overridingBlock.getSelectedItem().toString();
    }

    private VirtualFile getSelectedLayoutFile() {
        for (final VirtualFile layoutFile : selectedLayouts) {
            if (layoutFile.getName().equals(this.layout.getSelectedItem().toString())) {
                return layoutFile;
            }
        }

        return null;
    }

    /**
     * Open popup.
     *
     * @param project Project
     * @param psiFile PsiFile
     */
    public static void open(final @NotNull Project project, final @NotNull PsiFile psiFile) {
        final OverrideTemplateInModuleDialog dialog =
                new OverrideTemplateInModuleDialog(project, psiFile);
        dialog.pack();
        dialog.centerDialog(dialog);
        dialog.setVisible(true);
    }

    private void changeSelectLayoutComboBox(final String selectedBlock) {
        final Collection<VirtualFile> virtualFiles =
                FileBasedIndex.getInstance().getContainingFiles(
                        BlockNameIndex.KEY,selectedBlock,
                        GlobalSearchScope.allScope(project)
                );

        if (virtualFiles.isEmpty()) {
            return;
        }
        selectLayout.setVisible(true);
        layout.setVisible(true);
        fillLayoutOptions(virtualFiles);
    }
}
