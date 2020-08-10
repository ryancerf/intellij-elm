package org.elm.ide.refactoring

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.lang.Language
import com.intellij.lang.refactoring.InlineActionHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.refactoring.util.CommonRefactoringUtil
import org.elm.lang.core.ElmLanguage
import org.elm.lang.core.buildIndentedText
import org.elm.lang.core.psi.ElmPsiFactory
import org.elm.lang.core.psi.elements.ElmFunctionDeclarationLeft
import org.elm.lang.core.psi.elements.ElmLetInExpr
import org.elm.lang.core.psi.elements.ElmValueDeclaration
import org.elm.lang.core.psi.parentOfType
import org.elm.openapiext.runWriteCommandAction


class ElmInlineActionHandler : InlineActionHandler() {
    override fun isEnabledForLanguage(l: Language?): Boolean =
            l == ElmLanguage

    override fun isEnabledOnElement(element: PsiElement): Boolean =
            canInlineElement(element)

    override fun isEnabledOnElement(element: PsiElement, editor: Editor?): Boolean =
            isEnabledOnElement(element)

    override fun canInlineElementInEditor(element: PsiElement, editor: Editor?): Boolean =
            canInlineElement(element)

    override fun canInlineElement(element: PsiElement): Boolean =
            element is ElmFunctionDeclarationLeft
                    && !element.isTopLevel
                    && element.namedParameters.isEmpty()

    override fun inlineElement(project: Project, editor: Editor, element: PsiElement) {
        val function = element as ElmFunctionDeclarationLeft
        val valueDecl = function.parentOfType<ElmValueDeclaration>()
                ?: return errorHint(project, editor, "failed to find parent value decl")

        val reference = TargetElementUtil.findReference(editor, editor.caretModel.offset)
                ?: return errorHint(project, editor, "find ref failed")

        // TODO [kl] only allow this when the ref and the decl are part of the same function

        val letInExpr: ElmLetInExpr = valueDecl.parentOfType()
                ?: return errorHint(project, editor, "not in a let/in expr")

        val inlinableContent = valueDecl.expression!!.text
                .let {
                    if (' ' in it && (!it.startsWith('[') && !(it.startsWith('{')))) {
                        "($it)"
                    } else {
                        it
                    }
                }

        val declsToKeep = letInExpr.valueDeclarationList.filter { it !== valueDecl }

        project.runWriteCommandAction {
            // We are going to inline the decl, removing it from its let/in expr. If it was the last
            // remaining decl in the `let`, then remove the `let` entirely.
            val code = buildIndentedText(letInExpr) {
                if (declsToKeep.isNotEmpty()) {
                    appendLine("let")
                    level++
                    for ((idx, decl) in declsToKeep.withIndex()) {
                        appendElement(decl)
                        if (idx != declsToKeep.size - 1) appendLine()
                    }
                    level--
                    appendLine("in")
                }
                appendElementSubstituting(letInExpr.expression!!, reference.element, inlinableContent)
            }
            println(code)
            letInExpr.replace(ElmPsiFactory(project).createExpr(code))
        }
    }

    private fun errorHint(project: Project, editor: Editor, message: String) {
        CommonRefactoringUtil.showErrorHint(
                project,
                editor,
                message,
                "inline.method.title",
                "refactoring.inlineMethod")
    }
}