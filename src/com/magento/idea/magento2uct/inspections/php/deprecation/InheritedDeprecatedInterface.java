/*
 * Copyright © Magento, Inc. All rights reserved.
 * See COPYING.txt for license details.
 */

package com.magento.idea.magento2uct.inspections.php.deprecation;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.jetbrains.php.lang.inspections.PhpInspection;
import com.jetbrains.php.lang.psi.elements.ClassReference;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.visitors.PhpElementVisitor;
import com.magento.idea.magento2uct.inspections.UctProblemsHolder;
import com.magento.idea.magento2uct.packages.SupportedIssue;
import com.magento.idea.magento2uct.versioning.VersionStateManager;
import org.jetbrains.annotations.NotNull;

public class InheritedDeprecatedInterface extends PhpInspection {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            final @NotNull ProblemsHolder problemsHolder,
            boolean isOnTheFly
    ) {
        return new PhpElementVisitor() {

            @Override
            public void visitPhpClass(final PhpClass clazz) {
                if (!clazz.isInterface()) {
                    return;
                }
                for (final ClassReference ref : clazz.getExtendsList().getReferenceElements()) {
                    final String interfaceFqn = ref.getFQN();
                    final PsiElement interfaceClass = ref.resolve();

                    if (interfaceFqn == null || !(interfaceClass instanceof PhpClass)) {
                        continue;
                    }
                    Pair<Boolean, String> parentCheckResult = null;

                    if (VersionStateManager.getInstance().isDeprecated(interfaceFqn)
                            || (parentCheckResult = hasDeprecatedParent(
                                    (PhpClass) interfaceClass
                    )).getFirst()) {
                        if (problemsHolder instanceof UctProblemsHolder) {
                            ((UctProblemsHolder) problemsHolder).setReservedErrorCode(
                                    SupportedIssue.INHERITED_DEPRECATED_INTERFACE.getCode()
                            );
                        }
                        problemsHolder.registerProblem(
                                ref,
                                SupportedIssue.INHERITED_DEPRECATED_INTERFACE.getMessage(
                                        parentCheckResult == null
                                                ? interfaceFqn
                                                : parentCheckResult.getSecond()
                                ),
                                ProblemHighlightType.LIKE_DEPRECATED
                        );
                    }
                }
            }
        };
    }

    /**
     * Check if specified interface inherited from deprecated parent.
     *
     * @param interfaceClass PhpClass
     *
     * @return Pair[Boolean, String]
     */
    public static Pair<Boolean, String> hasDeprecatedParent(final PhpClass interfaceClass) {
        if (!interfaceClass.isInterface()) {
            return new Pair<>(false, null);
        }
        for (final ClassReference interfaceRef
                : interfaceClass.getExtendsList().getReferenceElements()) {
            final String interfaceFqn = interfaceRef.getFQN();

            if (interfaceFqn == null) {
                continue;
            }

            if (VersionStateManager.getInstance().isDeprecated(interfaceFqn)) {
                return new Pair<>(true, interfaceFqn);
            }
        }

        for (final PhpClass parent : interfaceClass.getImplementedInterfaces()) {
            final Pair<Boolean, String> checkResult = hasDeprecatedParent(parent);

            if (checkResult.getFirst()) {
                return checkResult;
            }
        }

        return new Pair<>(false, null);
    }
}