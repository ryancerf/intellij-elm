package org.elm.lang.core

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import org.elm.lang.core.psi.*
import org.elm.utils.getIndent
import kotlin.math.ceil

/**
 * Convert a string so that its first character is guaranteed to be lowercase.
 * This is necessary in some parts of Elm's syntax (e.g. a function parameter).
 *
 * If the receiver consists of all uppercase letters, the entire thing will be made
 * lowercase (because "uuid" is a far more sensible transformation of "UUID" than "uUID").
 */
fun String.toElmLowerId(): String =
        when {
            isEmpty() -> ""
            all { it.isUpperCase() } -> toLowerCase()
            else -> first().toLowerCase() + substring(1)
        }

/**
 * Build a string of indented text lines. Useful for multi-line code generation.
 *
 * @see buildIndentedText
 */
class IndentedTextBuilder(startLevel: Int, val indentSize: Int) {
    var level: Int = startLevel
    private var buffer = StringBuilder()

    fun build() = buffer.toString()

    private fun appendInternal(str: String = "") {
        if (str.isBlank()) {
            buffer.appendln()
            return
        }
        buffer.append(" ".repeat(level * indentSize))
        buffer.appendln(str)
    }

    fun appendLine(str: String = "") {
        require('\n' !in str) {
            "If you're trying to append the contents of a PsiElement, use `appendElement()` instead"
        }
        appendInternal(str)
    }

    fun appendElement(element: PsiElement?) {
        if (element == null) return
        for (line in element.textWithNormalizedIndents.lines()) {
            appendLine(line)
        }
    }

    fun appendElementSubstituting(
            element: PsiElement,
            target: PsiElement,
            replacement: PsiElement,
            transform: (String) -> String = { s -> s }
    ) {
        // get the original range of `target`
        val range = element.rangeFor(target)

        // normalize the indents in `element` and compute adjusted range


        // normalize the indents in `replacement`

        // replace the text using the adjusted range

        appendInternal("")
    }
}

/**
 * Build a string of indented text relative to [element]'s current level of indentation.
 *
 * The indent size is determined based on the user's preference in IntelliJ code style settings.
 */
fun buildIndentedText(element: PsiElement, builder: (IndentedTextBuilder).() -> Unit): String {
    val doc = PsiDocumentManager.getInstance(element.project).getDocument(element.containingFile)
            ?: error("Failed to find document for $element")
    val existingIndent = doc.getIndent(element.startOffset)
    val indentSize = element.indentStyle.INDENT_SIZE
    val startLevel = ceil(existingIndent.length / indentSize.toDouble()).toInt()
    val b = IndentedTextBuilder(startLevel, indentSize)
    b.builder()
    return b.build()
}

/**
 * Returns the element's text content where each line has been normalized such that:
 *
 *   1) it starts with a non-whitespace character
 *   2) relative indentation is preserved
 *
 * This is useful when manually building strings involving multi-line Elm expressions and declarations.
 */
private val PsiElement.textWithNormalizedIndents: String
    get() {
        val firstColumn = StringUtil.offsetToLineColumn(this.containingFile.text, this.startOffset).column
        return this.text.lines().mapIndexed { index: Int, s: String ->
            if (index == 0) s else s.drop(firstColumn)
        }.joinToString("\n")
    }

private fun PsiElement.textReplacing(child: PsiElement, newText: String): String {
    if (child === this) return newText
    require(child in descendants)
    val myText = text
    val start = child.offsetIn(this)
    val end = start + child.textLength
    return myText.replaceRange(start, end, newText)
}

private fun PsiElement.rangeFor(child: PsiElement): IntRange {
    require(child in descendants)
    val start = child.offsetIn(this)
    val end = start + child.textLength
    return IntRange(start, end - 1)
}
