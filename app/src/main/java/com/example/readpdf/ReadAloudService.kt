package com.example.readpdf

import android.app.*
import android.content.Intent
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.*
import android.speech.tts.UtteranceProgressListener

class ReadAloudService : Service() {

    private lateinit var tts: TextToSpeech
    private var sentences: List<String> = emptyList()
    private var currentIndex = 0
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    override fun onCreate() {
        super.onCreate()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.JAPANESE
                Log.d("ReadAloudService", "TTS initialized")
            }
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // 読み上げ開始時の処理（必要であれば）
            }

            override fun onDone(utteranceId: String?) {
                // 読み上げ完了時の処理
                currentIndex++
                speakNext()
            }

            override fun onError(utteranceId: String?) {
                // エラー時の処理
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sentenceList = intent?.getStringArrayListExtra("sentences")
        val startIndex = intent?.getIntExtra("startIndex", 0) ?: 0

        if (sentenceList.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        sentences = sentenceList
        currentIndex = startIndex

        startForegroundWithNotification()
        speakSentence(sentences[currentIndex])

        return START_STICKY
    }

    private fun speakNext() {
        if (currentIndex < sentences.size) {
            val text = sentences[currentIndex]
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utterance_$currentIndex")
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utterance_$currentIndex")
        } else {
            stopSelf()
        }
    }

    private fun speakSentence(text: String) {
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utterance_$currentIndex")
    }

    private fun startForegroundWithNotification() {
        val channelId = "readpdf_channel"
        val channelName = "読み上げサービス"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("読み上げ中")
            .setContentText("アプリのテキストを読み上げています")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
