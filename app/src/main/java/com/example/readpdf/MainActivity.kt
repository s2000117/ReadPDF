package com.example.readpdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.readpdf.ui.theme.ReadPDFTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.*
import android.speech.tts.UtteranceProgressListener

class MainActivity : AppCompatActivity() {
    private lateinit var tts: TextToSpeech
    private var pdfFile by mutableStateOf<File?>(null)
    private var onSpeakDone: (() -> Unit)? = null
    private var currentSentenceIndex = 0
    private var sentences: List<String> = emptyList()
    private var sentencePageMap: List<Int> = emptyList()
    private var pageSentencePositions: List<Float> = emptyList()
    private var speechRateState = mutableStateOf(1.0f)
    private var currentPage = mutableStateOf(0)
    private var totalPages = 0
    private lateinit var sharedPreferences: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences("readaloud_prefs", Context.MODE_PRIVATE)
        currentSentenceIndex = sharedPreferences.getInt("sentence_index", 0)

        PDFBoxResourceLoader.init(applicationContext)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.JAPANESE
                val savedRate = sharedPreferences.getFloat("speech_rate", 1.0f)
                tts.setSpeechRate(savedRate)
                speechRateState.value = savedRate
            }
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // 開始時の処理（必要なら）
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread { onSpeakDone?.invoke() }
            }

            override fun onError(utteranceId: String?) {
                Log.e("TTS", "Error in utterance: $utteranceId")
            }
        })

        val savedPath = sharedPreferences.getString("pdf_path", null)
        val savedPage = sharedPreferences.getInt("current_page", 0)
        val savedSentence = sharedPreferences.getInt("sentence_index", 0)
        if (savedPath != null) {
            val file = File(savedPath)
            if (file.exists()) {
                pdfFile = file
                currentPage.value = savedPage
                currentSentenceIndex = savedSentence
                lifecycleScope.launch {
                    startReadingFromPage(savedPage, sentenceIndex = savedSentence)
                }
            }
        }

        val pickPdfLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                Toast.makeText(this, "ファイルが選択されませんでした", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            val tempFile = copyUriToFile(uri)
            if (tempFile != null) {
                pdfFile = tempFile

                val previousPath = sharedPreferences.getString("pdf_path", null)
                val isSameFile = previousPath == tempFile.absolutePath

                // 新しいファイルであれば初期化
                if (!isSameFile) {
                    currentPage.value = 0
                    currentSentenceIndex = 0
                    sharedPreferences.edit()
                        .putString("pdf_path", tempFile.absolutePath)
                        .putInt("current_page", 0)
                        .putInt("sentence_index", 0)
                        .apply()
                } else {
                    currentPage.value = sharedPreferences.getInt("current_page", 0)
                    currentSentenceIndex = sharedPreferences.getInt("sentence_index", 0)
                }

                lifecycleScope.launch {
                    startReadingFromPage(currentPage.value, currentSentenceIndex)
                }
            }
        }

        val composeView = ComposeView(this).apply {
            setContent {
                ReadPDFTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        var progress by remember { mutableStateOf(0f) }
                        var isSpeaking by remember { mutableStateOf(false) }
                        var stopRequested by remember { mutableStateOf(false) }
                        val scope = rememberCoroutineScope()

                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("速度:", modifier = Modifier.padding(end = 4.dp))
                                Slider(
                                    value = speechRateState.value,
                                    onValueChange = {
                                        speechRateState.value = it
                                        tts.setSpeechRate(it)
                                        sharedPreferences.edit().putFloat("speech_rate", it).apply()
                                    },
                                    valueRange = 0.5f..2.0f,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (isSpeaking) {
                                    Button(
                                        onClick = {
                                            tts.stop()
                                            isSpeaking = false
                                        }
                                    ) {
                                        Text("⏸️ 停止")
                                    }
                                } else if (sentences.isNotEmpty() && currentSentenceIndex < sentences.size) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                isSpeaking = true
                                                speakSentences()
                                            }
                                        }
                                    ) {
                                        Text("▶️ 再開")
                                    }
                                }
                            }

                            Row(modifier = Modifier.padding(8.dp)) {
                                Button(onClick = { pickPdfLauncher.launch("application/pdf") }) {
                                    Text("📂 PDFファイルを選択")
                                }
                            }

                            pdfFile?.let { file ->
                                val document = PDDocument.load(file)
                                val renderer = PDFRenderer(document)
                                totalPages = document.numberOfPages

                                PdfViewer(
                                    renderer = renderer,
                                    currentPage = currentPage.value,
                                    totalPages = totalPages,
                                    progress = progress,
                                    isSpeaking = isSpeaking,
                                    onPrev = {
                                        if (currentPage.value > 0) {
                                            currentPage.value--
                                            scope.launch { startReadingFromPage(currentPage.value) }
                                        }
                                    },
                                    onNext = {
                                        if (currentPage.value < totalPages - 1) {
                                            currentPage.value++
                                            scope.launch { startReadingFromPage(currentPage.value) }
                                        }
                                    },
                                    onTap = { yRatio ->
                                        if (isSpeaking) {
                                            tts.stop()
                                            isSpeaking = false
                                        } else {
                                            val closest = pageSentencePositions.withIndex()
                                                .minByOrNull { (_, pos) -> kotlin.math.abs(pos - yRatio) }
                                            val startIndex = closest?.index ?: 0
                                            scope.launch {
                                                isSpeaking = true
                                                startReadingFromPage(currentPage.value, sentenceIndex = startIndex)
                                            }
                                        }
                                    }
                                )

                                document.close()
                            }
                        }
                    }
                }
            }
        }

        setContentView(composeView)
    }

    private fun copyUriToFile(uri: Uri): File? {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val tempFile = File.createTempFile("selected", ".pdf", cacheDir)
            inputStream?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun startReadingFromPage(page: Int, sentenceIndex: Int? = null) {
        pdfFile?.let { file ->
            val document = PDDocument.load(file)
            val (processed, pageMap, positions) = preprocessTextWithPageMap(document, page)
            document.close()

            sentences = processed
            sentencePageMap = pageMap
            pageSentencePositions = positions
            currentSentenceIndex = sentenceIndex ?: 0

            if (sentences.isEmpty()) {
                sentences = listOf("このページには読み上げ可能なテキストがありません。")
                sentencePageMap = listOf(page)
                pageSentencePositions = listOf(0f)
            }

            speakSentences()
        }
    }

    private suspend fun speakSentences() {
        while (currentSentenceIndex < sentences.size) {
            val sentence = sentences[currentSentenceIndex]
            val pageIndex = sentencePageMap.getOrElse(currentSentenceIndex) { 0 }
            if (currentPage.value != pageIndex) currentPage.value = pageIndex

            sharedPreferences.edit()
                .putInt("sentence_index", currentSentenceIndex)
                .putInt("current_page", currentPage.value)
                .apply()


            if (sentence == "__DELAY__") {
                delay(300)
                currentSentenceIndex++
                continue
            }

            val utteranceId = "sentence$currentSentenceIndex"
            val completion = CompletableDeferred<Unit>()
            onSpeakDone = { completion.complete(Unit) }
            tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
            completion.await()
            delay(100)
            currentSentenceIndex++
        }

        if (currentPage.value < totalPages - 1) {
            currentPage.value++
            CoroutineScope(Dispatchers.Main).launch {
                startReadingFromPage(currentPage.value)
            }
        }
    }

    private fun preprocessTextWithPageMap(document: PDDocument, page: Int): Triple<List<String>, List<Int>, List<Float>> {
        val resultSentences = mutableListOf<String>()
        val resultPageMap = mutableListOf<Int>()
        val resultPositions = mutableListOf<Float>()
        val stripper = PDFTextStripper()

        stripper.startPage = page + 1
        stripper.endPage = page + 1
        val text = stripper.getText(document)
            .replace("\r", "")
            .replace("\n", "　")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("([。！？])"), "$1\n")

        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        for ((index, line) in lines.withIndex()) {
            resultSentences.add(line)
            resultPageMap.add(page)
            resultPositions.add(index / lines.size.toFloat())
        }
        return Triple(resultSentences, resultPageMap, resultPositions)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
    }
}

@Composable
fun PdfViewer(
    renderer: PDFRenderer,
    currentPage: Int,
    totalPages: Int,
    progress: Float,
    isSpeaking: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTap: (Float) -> Unit
) {
    val bitmap: Bitmap? = remember(currentPage) {
        try {
            renderer.renderImageWithDPI(currentPage, 200f)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ページ ${currentPage + 1} / $totalPages",
            modifier = Modifier.padding(8.dp)
        )

        Row(modifier = Modifier.padding(8.dp)) {
            Button(onClick = onPrev, modifier = Modifier.weight(1f)) {
                Text("前のページ")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onNext, modifier = Modifier.weight(1f)) {
                Text("次のページ")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) {
                    detectTapGestures { offset: Offset ->
                        val yRatio = offset.y / size.height
                        onTap(yRatio)
                    }
                }
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            } ?: run {
                Text("ページの読み込みに失敗しました。", modifier = Modifier.align(Alignment.Center))
            }
        }

        if (isSpeaking) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }
    }
}
