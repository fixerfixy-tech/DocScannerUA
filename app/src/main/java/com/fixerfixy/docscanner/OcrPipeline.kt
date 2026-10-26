package com.fixerfixy.docscanner

/** Один варіант зображення для OCR. T — Bitmap у застосунку (у тестах може бути String). */
class ImageVariant<T>(
    val label: String,
    val create: () -> T,
    val release: (T) -> Unit = {}
)

class PipelineResult(
    val doc: ParsedDocument,
    val variantsUsed: Int,
    val labels: List<String>
)

/**
 * Послідовно розпізнає варіанти, зупиняється, щойно результат надійний (економить час),
 * і зливає результати. recognize викликає ваш існуючий OCR і повертає текст у callback
 * (працює і з синхронним, і з асинхронним розпізнавачем).
 */
object OcrPipeline {

    fun <T> run(
        variants: List<ImageVariant<T>>,
        recognize: (T, (String) -> Unit) -> Unit,
        onDone: (PipelineResult) -> Unit,
        stopWhenConfident: Boolean = true
    ) {
        val outcomes = ArrayList<ParseOutcome>()
        val labels = ArrayList<String>()

        fun finish() {
            if (outcomes.isEmpty()) {
                onDone(PipelineResult(DocumentParser.parse(""), 0, emptyList()))
            } else {
                onDone(PipelineResult(ResultMerger.merge(outcomes, labels), outcomes.size, labels.toList()))
            }
        }

        fun step(index: Int) {
            if (index >= variants.size) {
                finish()
                return
            }
            val v = variants[index]
            val img: T = try {
                v.create()
            } catch (e: Throwable) { // OutOfMemoryError тощо — пропускаємо варіант
                step(index + 1)
                return
            }
            var delivered = false
            val cb: (String) -> Unit = { text ->
                if (!delivered) {
                    delivered = true
                    try { v.release(img) } catch (ignored: Throwable) {}
                    val outcome = DocumentParser.parseWithConfidence(text)
                    outcomes.add(outcome)
                    labels.add(v.label)
                    if (stopWhenConfident && outcome.confident) finish() else step(index + 1)
                }
            }
            try {
                recognize(img, cb)
            } catch (e: Throwable) {
                cb("")
            }
        }
        step(0)
    }
}
