package com.example.readpdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.readpdf.ui.theme.ReadPDFTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.*
import java.util.*

class MainActivity : AppCompatActivity() {
    private var selectedFile: File? = null
    private var fileText: String = ""
    private lateinit var sharedPreferences: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        sharedPreferences = getSharedPreferences("readaloud_prefs", Context.MODE_PRIVATE)
        PDFBoxResourceLoader.init(applicationContext)

        val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                Toast.makeText(this, "ファイルが選択されませんでした", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            val tempFile = copyUriToFile(uri)
            if (tempFile != null) {
                selectedFile = tempFile
                lifecycleScope.launch {
                    fileText = extractText(tempFile, uri)
                    val sentences = fileText.split("。", "\n")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    speakInBackground(sentences, 0)
                }
            } else {
                Toast.makeText(this, "ファイルの読み込みに失敗しました", Toast.LENGTH_SHORT).show()
            }
        }

        val composeView = ComposeView(this).apply {
            setContent {
                ReadPDFTheme {
                    var speechRate by remember { mutableStateOf(sharedPreferences.getFloat("speech_rate", 1.0f)) }
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Button(onClick = { pickFileLauncher.launch("*/*") }) {
                                Text("ファイル選択（PDF または Word）")
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("速度:", modifier = Modifier.padding(end = 8.dp))
                                Slider(
                                    value = speechRate,
                                    onValueChange = {
                                        speechRate = it
                                        sharedPreferences.edit().putFloat("speech_rate", it).apply()
                                    },
                                    valueRange = 0.5f..2.0f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        setContentView(composeView)
    }

    private fun speakInBackground(sentences: List<String>, currentIndex: Int) {
        val intent = Intent(this, ReadAloudService::class.java).apply {
            putStringArrayListExtra("sentences", ArrayList(sentences))
            putExtra("startIndex", currentIndex)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun copyUriToFile(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val tempFile = File.createTempFile("selected", null, cacheDir)
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun extractText(file: File, uri: Uri): String = withContext(Dispatchers.IO) {
        return@withContext try {
            val type = contentResolver.getType(uri)
            when {
                type == "application/pdf" -> {
                    val doc = PDDocument.load(file)
                    val text = PDFTextStripper().apply {
                        startPage = 1
                        endPage = doc.numberOfPages
                    }.getText(doc)
                    doc.close()
                    text
                }
                type == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                    val fis = FileInputStream(file)
                    val doc = XWPFDocument(fis)
                    val text = doc.paragraphs.joinToString("\n") { it.text }
                    doc.close()
                    text
                }
                else -> "未対応のファイル形式です"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "テキスト抽出中にエラーが発生しました"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, ReadAloudService::class.java))
    }
}
