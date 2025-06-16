package com.example.readpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object PdfRendererHelper {

    fun renderPage(context: Context, uri: Uri, pageIndex: Int): Bitmap? {
        val fileDescriptor = getFileDescriptor(context, uri) ?: return null
        val pdfRenderer = PdfRenderer(fileDescriptor)

        return pdfRenderer.use { renderer ->
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
            val page = renderer.openPage(pageIndex)
            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bitmap
        }
    }

    fun getPageCount(context: Context, uri: Uri): Int {
        val fileDescriptor = getFileDescriptor(context, uri) ?: return 0
        return PdfRenderer(fileDescriptor).use { it.pageCount }
    }

    private fun getFileDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? {
        return try {
            // PDFを一時ファイルに保存し、読み込み
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val file = File(context.cacheDir, "temp.pdf")
            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            outputStream.close()
            inputStream?.close()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
