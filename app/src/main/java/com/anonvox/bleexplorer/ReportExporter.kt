package com.anonvox.bleexplorer

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Exports the same captured evidence in portable TXT, JSON and paginated PDF formats. */
object ReportExporter {
    fun shareAll(context: Context, title: String, baseName: String, text: String, json: String) {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val txtFile = File(dir, "$baseName.txt")
        val jsonFile = File(dir, "$baseName.json")
        val pdfFile = File(dir, "$baseName.pdf")
        txtFile.writeText(text, Charsets.UTF_8)
        jsonFile.writeText(json, Charsets.UTF_8)
        writePdf(pdfFile, title, text)
        val files = listOf(txtFile, jsonFile, pdfFile)
        val uris = ArrayList<Uri>()
        files.forEach { uris.add(FileProvider.getUriForFile(context, context.packageName + ".reports", it)) }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "Evidence export attached in TXT, JSON and PDF formats. Discovery indicators are not proof of identity or vulnerability.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newUri(context.contentResolver, "BLE Explorer reports", uris.first()).apply {
                uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
            }
        }
        context.startActivity(Intent.createChooser(send, "Share evidence reports"))
    }

    private fun writePdf(file: File, title: String, body: String) {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 42f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = android.graphics.Color.rgb(45, 45, 45) }
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f; isFakeBoldText = true; color = android.graphics.Color.rgb(45, 45, 45) }
        val lines = mutableListOf<String>()
        body.lines().forEach { source ->
            if (source.isBlank()) { lines.add(""); return@forEach }
            var rest = source
            while (rest.isNotEmpty()) {
                var end = rest.length
                while (end > 1 && paint.measureText(rest.substring(0, end)) > pageWidth - margin * 2) end--
                if (end < rest.length) {
                    val split = rest.lastIndexOf(' ', end).takeIf { it > 0 } ?: end
                    lines.add(rest.substring(0, split))
                    rest = rest.substring(split).trimStart()
                } else { lines.add(rest); rest = "" }
            }
        }
        var pageNumber = 1
        var index = 0
        while (index < lines.size || pageNumber == 1) {
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            val canvas = page.canvas
            canvas.drawText(title.take(70), margin, 48f, heading)
            canvas.drawText("BLE Explorer · Evidence report · Page $pageNumber", margin, 68f, paint)
            var y = 94f
            while (index < lines.size && y < pageHeight - 48f) {
                canvas.drawText(lines[index].take(150), margin, y, paint)
                y += 14f
                index++
            }
            document.finishPage(page)
            pageNumber++
        }
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
    }
}
