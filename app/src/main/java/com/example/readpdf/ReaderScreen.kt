// 改善された ReaderScreen.kt（PDF/Word両対応、自動再開・TTS最適化・進捗表示・安定性改善）

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.database.getStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.InputStream

@Composable
fun ReaderScreen(tts: TextToSpeech?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    var fileName by rememberSaveable { mutableStateOf(prefs.getString("fileName", "ファイル未選択") ?: "ファイル未選択") }
    var fileUri by rememberSaveable { mutableStateOf(prefs.getString("fileUri", null)?.let { Uri.parse(it) }) }
    var currentIndex by rememberSaveable { mutableStateOf(prefs.getInt("currentIndex", 0)) }
    var isPlaying by rememberSaveable { mutableStateOf(false) }
    var speed by rememberSaveable { mutableStateOf(1.0f) }
    var currentPage by rememberSaveable { mutableStateOf(0) }
    var totalPages by remember { mutableStateOf(0) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var paragraphs by remember { mutableStateOf<List<String>>(emptyList()) }
    val listState = rememberLazyListState()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                fileUri = it
                fileName = getFileName(context, it)
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString("fileUri", it.toString()).putString("fileName", fileName).putInt("currentIndex", 0).apply()
                currentIndex = 0
                scope.launch {
                    if (fileName.endsWith(".pdf", true)) {
                        totalPages = PdfRendererHelper.getPageCount(context, it)
                        currentPage = 0
                        pageBitmap = PdfRendererHelper.renderPage(context, it, 0)
                        paragraphs = PdfTextExtractor.extractTextFromPage(context, it, 0)
                            .split(Regex("(?<=[。．？！])\\s*|\\n+")).filter { s -> s.isNotBlank() }
                    } else if (fileName.endsWith(".docx", true)) {
                        val text = extractDocxText(context.contentResolver.openInputStream(it))
                        pageBitmap = null
                        paragraphs = text.split(Regex("(?<=[。．？！])\\s*|\\n+")).filter { s -> s.isNotBlank() }
                    }
                }
            } catch (e: Exception) {
                Log.e("Launcher", "Failed to open file", e)
                fileUri = null
                fileName = "読み込み失敗"
            }
        }
    }

    LaunchedEffect(Unit) {
        fileUri?.let { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                fileName = getFileName(context, uri)
                if (fileName.endsWith(".pdf", true)) {
                    totalPages = PdfRendererHelper.getPageCount(context, uri)
                    currentPage = 0
                    pageBitmap = PdfRendererHelper.renderPage(context, uri, 0)
                    paragraphs = PdfTextExtractor.extractTextFromPage(context, uri, 0)
                        .split(Regex("(?<=[。．？！])\\s*|\\n+")).filter { s -> s.isNotBlank() }
                } else if (fileName.endsWith(".docx", true)) {
                    val text = extractDocxText(context.contentResolver.openInputStream(uri))
                    pageBitmap = null
                    paragraphs = text.split(Regex("(?<=[。．？！])\\s*|\\n+")).filter { s -> s.isNotBlank() }
                }
            } catch (e: Exception) {
                Log.e("AutoResume", "Failed to reopen file", e)
            }
        }
    }

    LaunchedEffect(tts) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (currentIndex + 1 < paragraphs.size) {
                    currentIndex++
                    prefs.edit().putInt("currentIndex", currentIndex).apply()
                    scope.launch { listState.animateScrollToItem(currentIndex) }
                    tts?.speak(paragraphs[currentIndex], TextToSpeech.QUEUE_FLUSH, null, "utt_$currentIndex")
                } else {
                    isPlaying = false
                    tts?.stop()
                }
            }
            override fun onError(utteranceId: String?) {
                Log.e("TTS", "Error: $utteranceId")
                isPlaying = false
                tts?.stop()
            }
        })
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                launcher.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            }) {
                Text("ファイル選択")
            }
            Spacer(Modifier.width(16.dp))
            Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.height(8.dp))
        Text("${if (isPlaying) "再生中" else "停止中"} (${currentIndex + 1}/${paragraphs.size})", color = if (isPlaying) Color.Green else Color.Gray)
        LinearProgressIndicator(progress = if (paragraphs.isNotEmpty()) currentIndex / (paragraphs.size - 1f) else 0f, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))

        pageBitmap?.let {
            Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(300.dp))
        } ?: LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            itemsIndexed(paragraphs) { idx, p ->
                Text(p, color = if (idx == currentIndex) Color.Yellow else Color.White, modifier = Modifier.clickable {
                    currentIndex = idx
                    prefs.edit().putInt("currentIndex", idx).apply()
                    tts?.stop()
                    tts?.speak(p, TextToSpeech.QUEUE_FLUSH, null, "utt_$idx")
                    isPlaying = true
                }.padding(8.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                if (!isPlaying && paragraphs.isNotEmpty()) {
                    tts?.setSpeechRate(speed)
                    tts?.speak(paragraphs[currentIndex], TextToSpeech.QUEUE_FLUSH, null, "utt_$currentIndex")
                    isPlaying = true
                } else {
                    tts?.stop()
                    isPlaying = false
                }
            }) {
                Text(if (!isPlaying) "再生" else "停止")
            }
            Spacer(Modifier.width(16.dp))
            Text("速度: %.1fx".format(speed))
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..2.0f, steps = 3, modifier = Modifier.width(150.dp))
        }
    }
}

fun getFileName(context: Context, uri: Uri): String {
    return context.contentResolver.query(uri, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getStringOrNull(it.getColumnIndex(OpenableColumns.DISPLAY_NAME)) ?: "不明"
        else "不明"
    } ?: "不明"
}

suspend fun extractDocxText(inputStream: InputStream?): String = withContext(Dispatchers.IO) {
    inputStream ?: return@withContext ""
    val doc = XWPFDocument(inputStream)
    val sb = StringBuilder()
    for (para in doc.paragraphs) sb.append(para.text).append("\n")
    doc.close()
    sb.toString()
}
