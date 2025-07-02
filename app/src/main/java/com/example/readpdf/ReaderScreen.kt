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
    var fileUri by rememberSaveable {
        mutableStateOf(prefs.getString("fileUri", null)?.let { Uri.parse(it) })
    }
    var currentIndex by rememberSaveable { mutableStateOf(prefs.getInt("currentIndex", 0)) }
    val isPlaying = rememberSaveable { mutableStateOf(false) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var docText by remember { mutableStateOf("") }
    var paragraphs by remember { mutableStateOf<List<String>>(emptyList()) }
    val listState = rememberLazyListState()
    val speed = rememberSaveable { mutableStateOf(1.0f) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            fileName = getFileName(context, it)
            fileUri = it
            currentIndex = 0
            prefs.edit().putString("fileUri", it.toString()).putString("fileName", fileName).putInt("currentIndex", 0).apply()

            if (fileName.endsWith(".pdf", true)) {
                val pageCount = PdfRendererHelper.getPageCount(context, it)
                pageBitmap = PdfRendererHelper.renderPage(context, it, 0)
                scope.launch {
                    docText = PdfTextExtractor.extractTextFromPage(context, it, 0)
                    paragraphs = docText.split(Regex("(?<=[。．？！])\\s*|\\n+")).filter { it.isNotBlank() }
                }
            } else if (fileName.endsWith(".docx", true)) {
                scope.launch {
                    val text = extractDocxText(context.contentResolver.openInputStream(it))
                    docText = text
                    paragraphs = text.split(Regex("(?<=[。．？！])\\s*|\\n+")).filter { it.isNotBlank() }
                    pageBitmap = null
                }
            }
        }
    }

    LaunchedEffect(fileUri) {
        fileUri?.let { uri ->
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            fileName = getFileName(context, uri)
            currentIndex = prefs.getInt("currentIndex", 0)

            if (fileName.endsWith(".pdf", true)) {
                pageBitmap = PdfRendererHelper.renderPage(context, uri, 0)
                docText = PdfTextExtractor.extractTextFromPage(context, uri, 0)
                paragraphs = docText.split(Regex("(?<=[。．？！])\\s*|\\n+")).filter { it.isNotBlank() }
            } else if (fileName.endsWith(".docx", true)) {
                val text = extractDocxText(context.contentResolver.openInputStream(uri))
                docText = text
                paragraphs = text.split(Regex("(?<=[。．？！])\\s*|\\n+")).filter { it.isNotBlank() }
                pageBitmap = null
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
                    isPlaying.value = false
                }
            }
            override fun onError(utteranceId: String?) {
                Log.e("TTS", "Error in utterance: $utteranceId")
                isPlaying.value = false
            }
        })
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                launcher.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            }) {
                Text("ファイルを選択")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (isPlaying.value) "再生中: $fileName" else "停止中",
            color = if (isPlaying.value) Color.Green else Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(onClick = {
                if (!isPlaying.value && paragraphs.isNotEmpty()) {
                    tts?.setSpeechRate(speed.value)
                    tts?.speak(paragraphs[currentIndex], TextToSpeech.QUEUE_FLUSH, null, "utt_$currentIndex")
                    isPlaying.value = true
                } else {
                    tts?.stop()
                    isPlaying.value = false
                }
            }) {
                Text(if (!isPlaying.value) "再生" else "停止")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        val bitmap = pageBitmap
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(400.dp))
        } else if (paragraphs.isNotEmpty()) {
            var sliderPosition by remember { mutableStateOf(currentIndex.toFloat()) }

            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                onValueChangeFinished = {
                    currentIndex = sliderPosition.toInt().coerceIn(0, paragraphs.lastIndex)
                    prefs.edit().putInt("currentIndex", currentIndex).apply()
                    tts?.stop()
                    tts?.speak(paragraphs[currentIndex], TextToSpeech.QUEUE_FLUSH, null, "utt_$currentIndex")
                    isPlaying.value = true
                },
                valueRange = 0f..paragraphs.lastIndex.toFloat(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )

            LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                itemsIndexed(paragraphs) { index, paragraph ->
                    Text(
                        text = paragraph,
                        color = if (index == currentIndex) Color.Yellow else Color.White,
                        modifier = Modifier.padding(8.dp).clickable {
                            currentIndex = index
                            prefs.edit().putInt("currentIndex", index).apply()
                            tts?.stop()
                            tts?.speak(paragraphs[currentIndex], TextToSpeech.QUEUE_FLUSH, null, "utt_$currentIndex")
                            isPlaying.value = true
                        }
                    )
                }
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
