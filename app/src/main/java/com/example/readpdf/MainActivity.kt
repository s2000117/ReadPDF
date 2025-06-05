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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var tts: TextToSpeech
    private var pdfFile by mutableStateOf<File?>(null)
    private var pdfFileName by mutableStateOf<String?>(null)
    private var onSpeakDone: (() -> Unit)? = null
    private var currentSentenceIndex = 0
    private var sentences: List<String> = emptyList()
    private var sentencePageMap: List<Int> = emptyList()
    private var pageSentencePositions: List<Float> = emptyList()
    private var speechRateState = mutableStateOf(1.0f)
    private var currentPage = mutableStateOf(0)
    private var totalPages = 0
    private lateinit var sharedPreferences: android.content.SharedPreferences
    private var pageTextCache = mutableMapOf<Int, List<String>>() // ページごとのテキストキャッシュ
    private var currentSentences: List<String> = emptyList()      // 現在ページの文リスト
    private var currentSentenceIndexState = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

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
                pdfFileName = getFileNameFromUri(uri)

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
                    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                        Box(
                            modifier = Modifier
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color(0xFF23272A), Color(0xFF393E46))
                                    )
                                )
                                .fillMaxSize()
                        ) {
                            var progress by remember { mutableStateOf(0f) }
                            var isSpeaking by remember { mutableStateOf(false) }
                            var stopRequested by remember { mutableStateOf(false) }
                            val scope = rememberCoroutineScope()

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // ファイル名表示（小さめ）
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 24.dp, bottom = 8.dp)
                                ) {
                                    Text(
                                        text = pdfFileName ?: "ファイルが選択されていません",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1976D2),
                                            fontSize = 18.sp
                                        ),
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }

                                // 速度スライダー（背景なし・薄く）
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "速度:",
                                        color = Color.White,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
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

                                // 再開・ファイル選択ボタンを横並び
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    if (isSpeaking) {
                                        Button(
                                            onClick = {
                                                tts.stop()
                                                isSpeaking = false
                                            },
                                            shape = RoundedCornerShape(24.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                                        ) {
                                            Text("⏸️ 停止", color = Color.White)
                                        }
                                    } else if (sentences.isNotEmpty() && currentSentenceIndex < sentences.size) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    isSpeaking = true
                                                    speakSentences()
                                                }
                                            },
                                            shape = RoundedCornerShape(24.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("再開", color = Color.White)
                                        }
                                    }
                                    Button(
                                        onClick = { pickPdfLauncher.launch("application/pdf") },
                                        shape = RoundedCornerShape(24.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF42A5F5)),
                                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("PDFファイルを選択", color = Color.White)
                                    }
                                }

                                // PDFビューア（最大化、黒背景なし）
                                pdfFile?.let { file ->
                                    val document = remember(file) { PDDocument.load(file) }
                                    val renderer = remember(document) { PDFRenderer(document) }
                                    DisposableEffect(document) {
                                        onDispose { document.close() }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight()
                                            .align(Alignment.CenterHorizontally)
                                    ) {
                                        PdfViewer(
                                            renderer = renderer,
                                            currentPage = currentPage.value,
                                            totalPages = totalPages,
                                            progress = progress,
                                            isSpeaking = isSpeaking,
                                            onPrev = {
                                                if (currentPage.value > 0) {
                                                    currentPage.value--
                                                    lifecycleScope.launch { startReadingFromPage(currentPage.value) }
                                                }
                                            },
                                            onNext = {
                                                if (currentPage.value < totalPages - 1) {
                                                    currentPage.value++
                                                    lifecycleScope.launch { startReadingFromPage(currentPage.value) }
                                                }
                                            },
                                            onTap = { yRatio ->
                                                // 現在ページ内の文リスト・位置リストから最も近い文を特定し、その文から再生
                                                val indicesOnPage = sentencePageMap.withIndex().filter { it.value == currentPage.value }.map { it.index }
                                                if (indicesOnPage.isNotEmpty()) {
                                                    val positionsOnPage = indicesOnPage.map { pageSentencePositions[it] }
                                                    val closest = positionsOnPage.withIndex().minByOrNull { (_, pos) -> kotlin.math.abs(pos - yRatio) }
                                                    val startIndex = closest?.index?.let { indicesOnPage[it] } ?: indicesOnPage.first()
                                                    lifecycleScope.launch {
                                                        isSpeaking = true
                                                        currentSentenceIndex = startIndex
                                                        speakSentences()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                            pageSentencePositions = pageSentencePositions,
                                            sentencePageMap = sentencePageMap,
                                            currentSentenceIndex = currentSentenceIndexState.value
                                        )
                                    }
                                }
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

    private fun loadPageTextAsync(page: Int, onLoaded: (List<String>) -> Unit) {
        lifecycleScope.launch {
            val sentences = pageTextCache[page] ?: withContext(Dispatchers.IO) {
                pdfFile?.let { file ->
                    val doc = PDDocument.load(file)
                    val (processed, _, _) = preprocessTextWithPageMap(doc, page)
                    doc.close()
                    pageTextCache[page] = processed
                    processed
                } ?: emptyList()
            }
            onLoaded(sentences)
            // 先読み（次ページ）
            preloadPageText(page + 1)
        }
    }

    private fun preloadPageText(page: Int) {
        if (pageTextCache.containsKey(page)) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                pdfFile?.let { file ->
                    val doc = PDDocument.load(file)
                    val (processed, _, _) = preprocessTextWithPageMap(doc, page)
                    doc.close()
                    pageTextCache[page] = processed
                }
            } catch (_: Exception) {}
        }
    }

    private fun onPageChanged(newPage: Int) {
        loadPageTextAsync(newPage) { sentences ->
            currentSentences = sentences
            currentSentenceIndex = 0
            // 必要ならUI更新や自動読み上げ開始
        }
    }

    private suspend fun startReadingFromPage(page: Int, sentenceIndex: Int? = null) {
        pdfFile?.let { file ->
            val document = PDDocument.load(file)
            totalPages = document.numberOfPages // ← ページ数をセット
            val allSentences = mutableListOf<String>()
            val allPageMap = mutableListOf<Int>()
            val allPositions = mutableListOf<Float>()
            for (p in 0 until totalPages) {
                val (processed, pageMap, positions) = preprocessTextWithPageMap(document, p)
                allSentences.addAll(processed)
                allPageMap.addAll(pageMap)
                allPositions.addAll(positions)
            }
            document.close()

            sentences = allSentences
            sentencePageMap = allPageMap
            pageSentencePositions = allPositions
            // 指定ページの最初の文にジャンプ
            val startIdx = allPageMap.indexOf(page).takeIf { it >= 0 } ?: 0
            currentSentenceIndex = sentenceIndex ?: startIdx

            if (sentences.isEmpty()) {
                sentences = listOf("このPDFには読み上げ可能なテキストがありません。")
                sentencePageMap = listOf(0)
                pageSentencePositions = listOf(0f)
            }

            speakSentences()
        }
    }

    private suspend fun speakSentences() {
        while (currentSentenceIndex < sentences.size) {
            val sentence = sentences[currentSentenceIndex]
            val pageIndex = sentencePageMap.getOrElse(currentSentenceIndex) { 0 }
            if (currentPage.value != pageIndex) currentPage.value = pageIndex // ページ同期

            sharedPreferences.edit()
                .putInt("sentence_index", currentSentenceIndex)
                .putInt("current_page", currentPage.value)
                .apply()

            if (sentence == "__DELAY__") {
                delay(300)
                currentSentenceIndex++
                currentSentenceIndexState.value = currentSentenceIndex
                continue
            }

            val utteranceId = "sentence$currentSentenceIndex"
            val completion = CompletableDeferred<Unit>()
            onSpeakDone = { completion.complete(Unit) }
            tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
            completion.await()
            delay(100)
            currentSentenceIndex++
            currentSentenceIndexState.value = currentSentenceIndex
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

    private fun getFileNameFromUri(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) result = it.getString(idx)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "PDFファイル"
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
    onTap: (Float) -> Unit,
    modifier: Modifier,
    pageSentencePositions: List<Float>,
    sentencePageMap: List<Int>,
    currentSentenceIndex: Int
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
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ページ ${currentPage + 1} / $totalPages",
            color = Color.White,
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

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val density = LocalDensity.current
            val maxHeightPx = with(density) { maxHeight.toPx() }
            bitmap?.let {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(maxHeightPx) {
                            detectTapGestures { offset: Offset ->
                                val yRatio = offset.y / maxHeightPx
                                onTap(yRatio)
                            }
                        }
                ) {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                    // ハイライト描画
                    val indicesOnPage = sentencePageMap.withIndex().filter { it.value == currentPage }.map { it.index }
                    if (indicesOnPage.isNotEmpty()) {
                        val positionsOnPage = indicesOnPage.map { pageSentencePositions[it] }
                        val highlightIdx = indicesOnPage.indexOf(currentSentenceIndex)
                        val highlightY = if (highlightIdx >= 0 && positionsOnPage.isNotEmpty()) positionsOnPage[highlightIdx] else null
                        highlightY?.let { yRatio ->
                            val highlightOffsetPx = yRatio * maxHeightPx
                            val highlightOffsetDp = with(density) { highlightOffsetPx.toDp() }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset(y = highlightOffsetDp)
                                    .height(24.dp)
                                    .background(Color(0x80FFF59D))
                            )
                        }
                    }
                }
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
