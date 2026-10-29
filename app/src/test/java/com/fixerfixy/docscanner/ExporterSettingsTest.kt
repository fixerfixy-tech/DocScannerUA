package com.fixerfixy.docscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExporterSettingsTest {

    private fun tmpDir(): File = Files.createTempDirectory("docscan").toFile()

    @Test fun txtNameFollowsPdfName() {
        val pdf = File("/x/Акт_надання_послуг_No_1_від_01.01.2026_p.pdf")
        assertEquals("Акт_надання_послуг_No_1_від_01.01.2026_p.txt", OcrTextExporter.txtFileFor(pdf).name)
    }

    @Test fun txtNameReplacesOnlyLastExtension() {
        assertEquals("Акт_No_1_від_01.01.2026_р.txt", OcrTextExporter.txtNameFor("Акт_No_1_від_01.01.2026_р.pdf"))
        assertEquals("Акт_No_1_від_01.01.2026_р_1.txt", OcrTextExporter.txtNameFor("Акт_No_1_від_01.01.2026_р_1.jpg"))
        assertEquals("без_розширення.txt", OcrTextExporter.txtNameFor("без_розширення"))
    }

    @Test fun saveWritesUtf8NextToPdf() {
        val dir = tmpDir()
        val pdf = File(dir, "a.pdf").apply { writeText("pdf") }
        val txt = OcrTextExporter.save(pdf, "Акт надання послуг № 1450")
        assertNotNull(txt)
        assertEquals(dir, txt!!.parentFile)
        assertEquals("Акт надання послуг № 1450", txt.readText(Charsets.UTF_8))
        dir.deleteRecursively()
    }

    @Test fun saveToMissingFolderReturnsNullWithoutCrash() {
        assertNull(OcrTextExporter.save(File("/nonexistent_dir_xyz/a.pdf"), "t"))
    }

    @Test fun renameMovesTxtAlong() {
        val dir = tmpDir()
        val oldPdf = File(dir, "old.pdf")
        val newPdf = File(dir, "new.pdf")
        OcrTextExporter.save(oldPdf, "текст")
        assertTrue(OcrTextExporter.moveAlong(oldPdf, newPdf))
        assertFalse(File(dir, "old.txt").exists())
        assertEquals("текст", File(dir, "new.txt").readText())
        dir.deleteRecursively()
    }

    @Test fun renameOverwritesStaleTarget() {
        val dir = tmpDir()
        File(dir, "new.txt").writeText("старий")
        OcrTextExporter.save(File(dir, "old.pdf"), "свіжий")
        OcrTextExporter.moveAlong(File(dir, "old.pdf"), File(dir, "new.pdf"))
        assertEquals("свіжий", File(dir, "new.txt").readText())
        dir.deleteRecursively()
    }

    @Test fun moveWithoutTxtReturnsFalse() {
        val dir = tmpDir()
        assertFalse(OcrTextExporter.moveAlong(File(dir, "a.pdf"), File(dir, "b.pdf")))
        dir.deleteRecursively()
    }

    @Test fun deleteRemovesTxt() {
        val dir = tmpDir()
        val pdf = File(dir, "a.pdf")
        OcrTextExporter.save(pdf, "t")
        assertTrue(OcrTextExporter.delete(pdf))
        assertFalse(OcrTextExporter.delete(pdf))
        dir.deleteRecursively()
    }

    // ---------- налаштування ----------
    private class MemStore : KeyValueStore {
        val m = HashMap<String, Boolean>()
        override fun getBoolean(key: String, default: Boolean) = m[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { m[key] = value }
    }

    @Test fun defaults_txtOff_multiChannelOn() {
        val s = AppSettings(MemStore())
        assertFalse(s.saveOcrText)
        assertTrue(s.multiChannelOcr)
    }

    @Test fun togglesPersistInStore() {
        val store = MemStore()
        AppSettings(store).apply { saveOcrText = true; multiChannelOcr = false }
        val again = AppSettings(store)
        assertTrue(again.saveOcrText)
        assertFalse(again.multiChannelOcr)
    }

    @Test fun channelModesDependOnSetting() {
        val s = AppSettings(MemStore())
        assertEquals(4, s.channelModes().size)
        s.multiChannelOcr = false
        assertEquals(listOf(ChannelMode.ORIGINAL), s.channelModes())
    }
}
