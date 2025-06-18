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

    var fileName by remember { mutableStateOf(prefs.getString("last_file", "ファイル未選択") ?: "ファイル未選択") }
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var docText by remember { mutableStateOf("") }
    var paragraphs by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(prefs.getInt("last_index", 0)) }
    var currentPage by remember { mutableStateOf(0) }
    var totalPages by remember { mutableStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1.0f) }
    val listState = rememberLazyListState()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            fileUri = it
            fileName = getFileName(context, it)
            prefs.edit().putString("last_file", fileName).apply()

            when {
                fileName.endsWith(".pdf", true) -> {
                    val pageCount = PdfRendererHelper.getPageCount(context, it)
                    totalPages = pageCount
                    currentPage = 0
                    pageBitmap = PdfRendererHelper.renderPage(context, it, 0)
                    scope.launch {
                        docText = PdfTextExtractor.extractTextFromPage(context, it, 0)
                        paragraphs = docText.split(Regex("(?<=[。．？！])\\s*|\\n+")).filter { it.isNotBlank() }
                        currentIndex = 0
                    }
                }
                fileName.endsWith(".docx", true) -> {
                    scope.launch {
                        val text = extractDocxText(context.contentResolver.openInputStream(it))
                        docText = text
                        paragraphs = text.split(Regex("(?<=[。．？！])\\s*|\\n+")).filter { it.isNotBlank() }
                        currentIndex = prefs.getInt("last_index", 0).coerceAtMost(paragraphs.lastIndex)
                        pageBitmap = null
                        totalPages = 1
                        currentPage = 0
                    }
                }
                else -> {
                    docText = "未対応のファイル形式です"
                    paragraphs = emptyList()
                    pageBitmap = null
                }
            }
        }
    }

    DisposableEffect(tts) {
        if (tts != null) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (currentIndex + 1 < paragraphs.size) {
                        currentIndex++
                        prefs.edit().putInt("last_index", currentIndex).apply()
                        scope.launch { listState.animateScrollToItem(currentIndex) }
                        tts.speak(paragraphs[currentIndex], TextToSpeech.QUEUE_FLUSH, null, "utt_$currentIndex")
                    } else {
                        isPlaying = false
                    }
                }
                override fun onError(utteranceId: String?) {
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

        Text(if (isPlaying) "再生中: $fileName" else "停止中", color = if (isPlaying) Color.Green else Color.Gray)

        val bitmap = pageBitmap
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(400.dp))
        } else if (paragraphs.isNotEmpty()) {
            LaunchedEffect(currentIndex) {
                listState.animateScrollToItem(currentIndex)
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(paragraphs) { index, paragraph ->
                    Text(
                        text = paragraph,
                        color = if (index == currentIndex) Color.Red else Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .clickable {
                                currentIndex = index
                                prefs.edit().putInt("last_index", index).apply()
                                tts?.stop()
                                tts?.speak(paragraphs[index], TextToSpeech.QUEUE_FLUSH, null, "utt_$index")
                                isPlaying = true
                            }
                    )
                }
            }
        }

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
            Spacer(modifier = Modifier.width(16.dp))
            Text("速度: %.1fx".format(speed))
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.5f..2.0f, steps = 3, modifier = Modifier.width(150.dp))
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
