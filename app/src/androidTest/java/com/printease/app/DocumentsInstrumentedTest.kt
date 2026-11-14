package com.printease.app

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android's PDF engine is not implemented by Robolectric. Run on an emulator/phone. */
@RunWith(AndroidJUnit4::class)
class DocumentsInstrumentedTest {
    @Test
    fun actualPdfPageCountAndRendering() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val docs = Documents(context)
        val pdf = docs.sample("native-pdf-check", 2)
        val metadata = docs.inspect(pdf, "sample.pdf")
        assertEquals(2, metadata.pages)
        val bitmap = docs.render(pdf, 1)
        assertEquals(1000, bitmap.width)
        assertTrue(bitmap.height > 0)
        bitmap.recycle()
    }

    @Test
    fun systemPickerImportPreservesPdfAndPageCount() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val docs = Documents(context)
        val pdf = docs.sample("native-import-check", 3)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", pdf)
        val imported = docs.import(uri)
        assertEquals(3, imported.pages)
        assertArrayEquals(pdf.readBytes(), java.io.File(imported.path).readBytes())
        java.io.File(imported.path).delete()
    }
}
