package com.example.readpdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.database.getStringOrNull
import com.example.readpdf.PdfRendererHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.InputStream
import java.util.*

@Composable
fun ReaderScreen(tts: TextToSpeech?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fileName by remember { mutableStateOf("ファイル未選択") }
    var currentPage by remember { mutableStateOf(0) }
    var totalPages by remember { mutableStateOf(0) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var docText by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1.0f) }
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var speechQueue by remember { mutableStateOf(listOf<String>()) }
    var currentSpeechIndex by remember { mutableStateOf(0) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            fileUri = it
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            fileName = getFileName(context, it)

            when {
                fileName.endsWith(".pdf", ignoreCase = true) -> {
                    val pageCount = PdfRendererHelper.getPageCount(context, it)
                    totalPages = pageCount
                    currentPage = 0
                    pageBitmap = PdfRendererHelper.renderPage(context, it, 0)
                    scope.launch {
                        docText = PdfTextExtractor.extractTextFromPage(context, it, 0)
                    }
                }
                fileName.endsWith(".docx", ignoreCase = true) -> {
                    scope.launch {
                        val fullText = extractDocxText(context.contentResolver.openInputStream(it))
                        docText = fullText
                        speechQueue = fullText.split("。", "。\n", "。\r\n", "\n", "\r").filter { line -> line.isNotBlank() }
                        currentSpeechIndex = 0
                        pageBitmap = null
                        totalPages = 1
                        currentPage = 0
                    }
                }
                else -> {
                    docText = "未対応のファイル形式です"
                    pageBitmap = null
                    totalPages = 1
                    currentPage = 0
                }
            }
        }
    }

    DisposableEffect(tts) {
        if (tts != null) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    currentSpeechIndex++
                    if (currentSpeechIndex < speechQueue.size) {
                        val nextText = speechQueue[currentSpeechIndex]
                        tts.speak(nextText, TextToSpeech.QUEUE_FLUSH, null, "utt_$currentSpeechIndex")
                    } else {
                        isPlaying = false
                    }
                }
                override fun onError(utteranceId: String?) {
                    Log.e("TTS", "Error in utterance $utteranceId")
                    isPlaying = false
                }
            })
        }
        onDispose {}
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { launcher.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }) {
                Text("ファイルを選択")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Spacer(modifier = Modifier.height(4.dp))
        if (isPlaying) Text("再生中: $fileName", color = Color.Green)
        else Text("停止中", color = Color.Gray)

        Spacer(modifier = Modifier.height(16.dp))
        if (pageBitmap != null) {
            Image(bitmap = pageBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(400.dp))
        } else if (docText.isNotBlank()) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f)) {
                Text(docText)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                if (!isPlaying) {
                    tts?.setSpeechRate(speed)
                    if (speechQueue.isNotEmpty()) {
                        tts?.speak(speechQueue[currentSpeechIndex], TextToSpeech.QUEUE_FLUSH, null, "utt_$currentSpeechIndex")
                        isPlaying = true
                    } else {
                        val textToSpeak = docText.trim().ifBlank { "ページ${currentPage + 1}を表示中" }
                        tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "utt_default")
                        isPlaying = true
                    }
                } else {
                    tts?.stop()
                    isPlaying = false
                }
            }) {
                Text(if (!isPlaying) "再生" else "停止")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text("速度: %.1fx".format(speed))
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..2.0f, steps = 3, modifier = Modifier.width(150.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                if (fileUri != null && fileName.endsWith(".pdf", true)) {
                    if (currentPage > 0) {
                        currentPage--
                        pageBitmap = PdfRendererHelper.renderPage(context, fileUri!!, currentPage)
                        scope.launch {
                            docText = PdfTextExtractor.extractTextFromPage(context, fileUri!!, currentPage)
                        }
                    }
                }
            }) {
                Text("前のページ")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("${currentPage + 1} / ${totalPages}")
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                if (fileUri != null && fileName.endsWith(".pdf", true)) {
                    if (currentPage < totalPages - 1) {
                        currentPage++
                        pageBitmap = PdfRendererHelper.renderPage(context, fileUri!!, currentPage)
                        scope.launch {
                            docText = PdfTextExtractor.extractTextFromPage(context, fileUri!!, currentPage)
                        }
                    }
                }
            }) {
                Text("次のページ")
            }
        }
    }
}

fun getFileName(context: Context, uri: Uri): String {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    return cursor?.use {
        if (it.moveToFirst()) it.getStringOrNull(it.getColumnIndex(OpenableColumns.DISPLAY_NAME)) ?: "不明なファイル名"
        else "不明なファイル名"
    } ?: "不明なファイル名"
}

suspend fun extractDocxText(inputStream: InputStream?): String = withContext(Dispatchers.IO) {
    inputStream ?: return@withContext ""
    val doc = XWPFDocument(inputStream)
    val sb = StringBuilder()
    for (para in doc.paragraphs) {
        sb.append(para.text).append("\n")
    }
    doc.close()
    sb.toString()
}