package org.elm.ide.refactoring

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.introduce.inplace.InplaceVariableIntroducer
import com.intellij.refactoring.util.CommonRefactoringUtil
import org.elm.ide.utils.findExpressionAtCaret
import org.elm.ide.utils.findExpressionInRange
import org.elm.lang.core.buildIndentedText
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.elements.ElmAnonymousFunctionExpr
import org.elm.lang.core.psi.elements.ElmCaseOfBranch
import org.elm.lang.core.psi.elements.ElmLetInExpr
import org.elm.lang.core.psi.elements.ElmValueDeclaration
import org.elm.openapiext.runWriteCommandAction

class ElmIntroduceVariableHandler : RefactoringActionHandler {

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        if (file !is ElmFile) return
        val exprs = findCandidateExpressions(editor, file)
        when (exprs.size) {
            0 -> {
                val message = RefactoringBundle.message(if (editor.selectionModel.hasSelection())
                    "selected.block.should.represent.an.expression"
                else
                    "refactoring.introduce.selection.error"
                )
                val title = RefactoringBundle.message("introduce.variable.title")
                val helpId = "refactoring.extractVariable"
                CommonRefactoringUtil.showErrorHint(project, editor, message, title, helpId)
            }
            1 -> introduceVariable(editor, exprs.single())
            else -> showExpressionChooser(editor, exprs) { chosenExpr ->
                introduceVariable(editor, chosenExpr)
            }
        }
    }

    private fun findCandidateExpressions(editor: Editor, file: ElmFile): List<ElmExpressionTag> {
        val selection = editor.selectionModel
        return if (selection.hasSelection()) {
            // If the user has some text selected, make a single suggestion based on the selection
            listOfNotNull(findExpressionInRange(file, selection.selectionStart, selection.selectionEnd))
        } else {
            // Suggest nested expressions at caret position
            val expr = findExpressionAtCaret(file, editor.caretModel.offset) ?: return emptyList()
            expr.ancestors
                    .takeWhile { it !is ElmValueDeclaration && it !is ElmLetInExpr }
                    .filterIsInstance<ElmExpressionTag>()
                    .toList()
        }
    }

    private fun introduceVariable(editor: Editor, chosenExpr: ElmExpressionTag) {
        if (!chosenExpr.isValid) return
        val project = editor.project ?: return

        val replacer = ExpressionReplacer(project, editor, chosenExpr)
        val anchor = findAnchor(chosenExpr) ?: error("could not find a place to introduce variable")
        when {
            anchor is ElmLetInExpr && anchor !== chosenExpr -> replacer.extendExistingLet(anchor)
            else -> replacer.introduceLet(anchor)
        }
    }

    private fun findAnchor(chosenExpr: ElmExpressionTag): ElmPsiElement? {
        // find the nearest location where a let/in would be
        var current: PsiElement? = chosenExpr
        while (current != null) {
            when (current) {
                is ElmLetInExpr -> return current
                is ElmValueDeclaration -> return current.expression
                is ElmCaseOfBranch -> return current.expression
                is ElmAnonymousFunctionExpr -> {
                    if (current.expression in chosenExpr.ancestors) return current.expression
                }
            }
            current = current.parent
        }
        return null
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        // IntelliJ will never call this when introducing a variable
    }
}

/**
 * Manages the replacement of an expression with the appropriate let/in expr
 *
 * This class exists solely to make the calling code nicer by consolidating shared parameters.
 */
private class ExpressionReplacer(
        private val project: Project,
        private val editor: Editor,
        private val chosenExpr: ElmExpressionTag
) {
    private val psiFactory = ElmPsiFactory(project)
    private val suggestedNames = chosenExpr.suggestedNames()
    private val identifier = psiFactory.createLowerCaseIdentifier(suggestedNames.default)

    fun introduceLet(elementToReplace: PsiElement) =
            buildLet(elementToReplace, existingDecls = emptyList())

    fun extendExistingLet(letExpr: ElmLetInExpr) =
            buildLet(letExpr, existingDecls = letExpr.valueDeclarationList)

    private fun buildLet(elementToReplace: PsiElement, existingDecls: List<ElmValueDeclaration>) {
        val newIdentifierElement = project.runWriteCommandAction {
            val code = buildIndentedText(elementToReplace) {
                appendLine("let")
                level++
                for (decl in existingDecls) {
                    appendElement(decl)
                    appendLine()
                }
                appendLine("${identifier.text} =")
                level++
                appendElement(chosenExpr)
                level -= 2
                appendLine("in")
                if (existingDecls.isNotEmpty() && elementToReplace is ElmLetInExpr) {
                    appendElementSubstituting(elementToReplace.expression!!, chosenExpr, identifier)
                } else {
                    appendElementSubstituting(elementToReplace, chosenExpr, identifier)
                }
            }
            val newLetExpr = psiFactory.createLetInWrapper(code)
            val newLetElement = elementToReplace.replace(newLetExpr) as ElmLetInExpr
            moveEditorToNameElement(editor, newLetElement.valueDeclarationList.last())
        }

        if (newIdentifierElement != null) {
            ElmInplaceVariableIntroducer(newIdentifierElement, editor, project, "choose a name", emptyArray())
                    .performInplaceRefactoring(suggestedNames.all)
        }
    }
}


class ElmInplaceVariableIntroducer(
        elementToRename: PsiNamedElement,
        editor: Editor,
        project: Project,
        title: String,
        occurrences: Array<PsiElement>
) : InplaceVariableIntroducer<PsiElement>(elementToRename, editor, project, title, occurrences, null)


fun moveEditorToNameElement(editor: Editor, element: PsiElement?): PsiNamedElement? {
    val newName = element?.descendants
            ?.filterIsInstance<ElmNameIdentifierOwner>()
            ?.firstOrNull { it.nameIdentifier.elementType == ElmTypes.LOWER_CASE_IDENTIFIER }
    if (newName != null) {
        editor.caretModel.moveToOffset(newName.nameIdentifier.startOffset)
    }
    return newName
}
