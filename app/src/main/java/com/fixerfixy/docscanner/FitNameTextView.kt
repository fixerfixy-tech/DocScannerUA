package com.fixerfixy.docscanner

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Показує назву документа в один рядок: з варіантів DocNameFormatter.candidates
 * обирає перший, що вміщується за поточною шириною (враховує шрифт, чекбокс, поворот екрана).
 */
class FitNameTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var candidates: List<String> = emptyList()

    init {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun setFullName(name: String) {
        candidates = DocNameFormatter.candidates(name)
        contentDescription = name
        val avail = width - paddingLeft - paddingRight
        text = if (avail > 0) pick(avail) else candidates.first()
    }

    private fun pick(avail: Int): String {
        val p = paint
        for (c in candidates) {
            if (p.measureText(c) <= avail) return c
        }
        return candidates.last()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (candidates.isNotEmpty() && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val avail = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            if (avail > 0) {
                val chosen = pick(avail)
                if (text.toString() != chosen) text = chosen
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
