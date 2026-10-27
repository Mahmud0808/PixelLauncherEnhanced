package com.drdisagree.pixellauncherenhanced.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.text.inSpans
import androidx.core.view.updateLayoutParams
import com.drdisagree.pixellauncherenhanced.utils.MiscUtils.dpToPx
import com.google.android.material.color.MaterialColors
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import com.google.android.material.R as MaterialR

class ReleaseNotesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var rendered: String? = null

    init {
        orientation = VERTICAL
    }

    fun setNotes(markdown: String) {
        if (rendered == markdown) return
        rendered = markdown
        removeAllViews()
        var child = parse(markdown).firstChild
        while (child != null) {
            renderBlock(child, 0)
            child = child.next
        }
    }

    private fun renderBlock(node: Node, depth: Int) {
        when (node) {
            is Heading -> if (!isChangelogTitle(node)) addHeading(node)
            is ListBlock -> renderList(node, depth)
            is Paragraph -> addRow(null, inline(node), depth, PARAGRAPH_GAP)
            is FencedCodeBlock -> addCode(node.literal)
            is IndentedCodeBlock -> addCode(node.literal)
            is BlockQuote -> {
                var child = node.firstChild
                while (child != null) {
                    renderBlock(child, depth)
                    child = child.next
                }
            }
        }
    }

    private fun renderList(list: ListBlock, depth: Int) {
        var number = (list as? OrderedList)?.markerStartNumber ?: 1
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                val lead = if (list is BulletList) null else "${number++}."
                var child = item.firstChild
                var first = true
                while (child != null) {
                    when {
                        first && child is Paragraph -> addRow(
                            lead ?: BULLET,
                            inline(child),
                            depth,
                            if (depth > 0) NESTED_GAP else ITEM_GAP
                        )

                        else -> renderBlock(child, depth + 1)
                    }
                    first = false
                    child = child.next
                }
            }
            item = item.next
        }
    }

    private fun addHeading(node: Heading) {
        val text = inline(node)
        val emoji = leadingEmoji(text)
        val title = if (emoji != null) text.delete(0, emoji.length).trimStart() else text
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }

        val style = if (node.level <= 2) MaterialR.attr.textAppearanceTitleMedium
        else MaterialR.attr.textAppearanceTitleSmall
        if (emoji != null) row.addView(textView(style, MaterialR.attr.colorOnSurface).apply {
            this.text = emoji
            layoutParams = LayoutParams(dpToPx(LEAD_WIDTH), LayoutParams.WRAP_CONTENT)
        })
        row.addView(textView(style, androidx.appcompat.R.attr.colorPrimary).apply {
            this.text = title
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        addSection(row, if (childCount == 0) 0 else HEADING_GAP)
    }

    private fun addRow(lead: String?, text: CharSequence, depth: Int, gap: Int) {
        if (text.isBlank()) return
        val body = textView(MaterialR.attr.textAppearanceBodyMedium, MaterialR.attr.colorOnSurface).apply {
            this.text = text
            setLineSpacing(0f, LINE_SPACING)
            if (text is SpannableStringBuilder && text.getSpans(0, text.length, URLSpan::class.java).isNotEmpty()) {
                movementMethod = LinkMovementMethod.getInstance()
            }
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPaddingRelative(dpToPx(LEAD_WIDTH) * depth, 0, 0, 0)
        }
        when (lead) {
            null -> {}
            BULLET -> row.addView(bulletDot(body, depth))
            else -> row.addView(textView(MaterialR.attr.textAppearanceBodyMedium, androidx.appcompat.R.attr.colorPrimary).apply {
                this.text = lead
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LayoutParams(dpToPx(LEAD_WIDTH), LayoutParams.WRAP_CONTENT)
            })
        }
        row.addView(body)
        addSection(row, if (childCount == 0) 0 else gap)
    }

    private fun bulletDot(body: TextView, depth: Int): View {
        val size = dpToPx(if (depth > 0) NESTED_DOT else DOT)
        val metrics = body.paint.fontMetricsInt
        val lineHeight = metrics.descent - metrics.ascent
        return View(context).apply {
            background = GradientDrawable().apply {
                shape = if (depth > 0) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
                cornerRadius = size / 3f
                setColor(color(androidx.appcompat.R.attr.colorPrimary))
            }
            layoutParams = LayoutParams(size, size).apply {
                topMargin = (lineHeight - size) / 2
                marginStart = dpToPx(DOT_INSET)
                marginEnd = dpToPx(LEAD_WIDTH) - size - dpToPx(DOT_INSET)
            }
        }
    }

    private fun addCode(code: String) {
        val view = textView(MaterialR.attr.textAppearanceBodySmall, MaterialR.attr.colorOnSurfaceVariant).apply {
            text = code.trimEnd()
            typeface = Typeface.MONOSPACE
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12).toFloat()
                setColor(color(MaterialR.attr.colorSurfaceContainerHighest))
            }
        }
        addSection(view, if (childCount == 0) 0 else PARAGRAPH_GAP)
    }

    private fun addSection(view: View, gap: Int) {
        addView(view)
        view.updateLayoutParams<LayoutParams> {
            width = LayoutParams.MATCH_PARENT
            topMargin = dpToPx(gap)
        }
    }

    private fun textView(@AttrRes appearance: Int, @AttrRes textColor: Int) = TextView(context).apply {
        setTextAppearance(resolveStyle(appearance))
        setTextColor(color(textColor))
        includeFontPadding = false
    }

    private fun resolveStyle(@AttrRes attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.resourceId
    }

    private fun color(@AttrRes attr: Int) = MaterialColors.getColor(this, attr)

    private fun inline(parent: Node): SpannableStringBuilder =
        SpannableStringBuilder().apply { appendChildren(parent) }

    private fun SpannableStringBuilder.appendChildren(parent: Node) {
        var node = parent.firstChild
        while (node != null) {
            when (node) {
                is Text -> append(node.literal)
                is SoftLineBreak -> append(' ')
                is HardLineBreak -> append('\n')
                is Code -> inSpans(
                    TypefaceSpan("monospace"),
                    BackgroundColorSpan(color(MaterialR.attr.colorSurfaceContainerHighest))
                ) { append(node.literal) }

                is StrongEmphasis -> inSpans(StyleSpan(Typeface.BOLD)) { appendChildren(node) }
                is Emphasis -> inSpans(StyleSpan(Typeface.ITALIC)) { appendChildren(node) }
                is Link -> inSpans(URLSpan(node.destination)) { appendChildren(node) }
                is Image -> appendChildren(node)
                else -> appendChildren(node)
            }
            node = node.next
        }
    }

    private fun leadingEmoji(text: CharSequence): String? {
        if (text.isEmpty()) return null
        val first = Character.codePointAt(text, 0)
        if (Character.getType(first) != Character.OTHER_SYMBOL.toInt() &&
            Character.getType(first) != Character.SURROGATE.toInt()
        ) return null
        var end = Character.charCount(first)
        while (end < text.length) {
            val next = Character.codePointAt(text, end)
            if (next == ZWJ || next == VARIATION_SELECTOR || Character.getType(next) == Character.NON_SPACING_MARK.toInt() ||
                next in SKIN_TONES || (text[end - 1].code == ZWJ && Character.getType(next) == Character.OTHER_SYMBOL.toInt())
            ) {
                end += Character.charCount(next)
            } else break
        }
        return text.substring(0, end)
    }

    private fun isChangelogTitle(node: Heading): Boolean = CHANGELOG_TITLE.matches(inline(node).trim())

    companion object {
        private const val BULLET = "•"
        private const val LEAD_WIDTH = 24
        private const val DOT = 6
        private const val NESTED_DOT = 5
        private const val DOT_INSET = 4
        private const val HEADING_GAP = 20
        private const val PARAGRAPH_GAP = 12
        private const val ITEM_GAP = 8
        private const val NESTED_GAP = 6
        private const val LINE_SPACING = 1.1f
        private const val ZWJ = 0x200D
        private const val VARIATION_SELECTOR = 0xFE0F
        private val SKIN_TONES = 0x1F3FB..0x1F3FF
        private val CHANGELOG_TITLE = Regex("^cha\\w*log:?$", RegexOption.IGNORE_CASE)

        private val parser: Parser by lazy {
            Parser.builder().extensions(listOf(AutolinkExtension.create())).build()
        }

        private fun parse(markdown: String): Node = parser.parse(markdown.replace("\r\n", "\n"))

        fun hasContent(markdown: String): Boolean {
            var child = parse(markdown).firstChild
            while (child != null) {
                if (child !is Heading) return true
                val text = StringBuilder()
                var inner = child.firstChild
                while (inner != null) {
                    if (inner is Text) text.append(inner.literal)
                    inner = inner.next
                }
                if (!CHANGELOG_TITLE.matches(text.trim())) return true
                child = child.next
            }
            return false
        }
    }
}
