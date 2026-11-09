package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.ai.SuaBrain
import com.example.control.SuaDeviceController
import com.example.data.DeviceActionType
import com.example.data.MokoOperatingMode
import com.example.data.SuaPreferences
import com.example.data.SuaStateRepository
import com.example.data.SuaStatus
import com.example.util.HindiTransliterator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SuaVoiceService : Service() {

    companion object {
        const val CHANNEL_ID = "moko_background_voice_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_LISTENING = "com.example.service.ACTION_START_LISTENING"
        const val ACTION_START_SLEEP_MODE = "com.example.service.ACTION_START_SLEEP_MODE"
        const val ACTION_STOP_SERVICE = "com.example.service.ACTION_STOP_SERVICE"
        const val ACTION_EXECUTE_COMMAND = "com.example.service.ACTION_EXECUTE_COMMAND"
        const val EXTRA_COMMAND_TEXT = "extra_command_text"

        fun startService(context: Context) {
            startServiceInMode(context, MokoOperatingMode.ONLINE)
        }

        fun startServiceInMode(context: Context, mode: MokoOperatingMode) {
            val prefs = SuaPreferences(context)
            prefs.setSavedOperatingMode(mode)
            if (mode == MokoOperatingMode.OFFLINE) {
                stopService(context)
                return
            }

            if (!prefs.hasAcceptedVoicePrivacyConsent()) {
                SuaStateRepository.setOperatingMode(MokoOperatingMode.OFFLINE)
                SuaStateRepository.updateStatus(SuaStatus.IDLE)
                SuaStateRepository.updateServiceError(
                    "VOICE PRIVACY CONSENT REQUIRED // Please review and accept the Voice & Cloud Privacy Notice before activating ${mode.label} microphone listening."
                )
                SuaStateRepository.triggerUiNavigationAction("SHOW_PRIVACY_CONSENT")
                return
            }

            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMic) {
                SuaStateRepository.setOperatingMode(MokoOperatingMode.OFFLINE)
                SuaStateRepository.updateStatus(SuaStatus.ERROR)
                SuaStateRepository.updateServiceError(
                    "MIC PERMISSION REQUIRED // Grant Microphone permission before starting ${mode.label} mode."
                )
                SuaStateRepository.updateLiveTranscript("MIC PERMISSION REQUIRED // Grant Microphone access")
                SuaStateRepository.updateLastReply("PERMISSION ERROR // Microphone permission is required before starting ${mode.label} mode.")
                SuaVoiceSpeaker.speak("माफ़ कीजिए बॉस, माइक परमिशन के बिना मोको ${mode.label} मोड चालू नहीं हो सकता।")
                return
            }

            SuaStateRepository.updateServiceError(null)
            val intent = Intent(context, SuaVoiceService::class.java).apply {
                action = if (mode == MokoOperatingMode.SLEEP) {
                    ACTION_START_SLEEP_MODE
                } else {
                    ACTION_START_LISTENING
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                try {
                    context.startService(intent)
                } catch (e2: Exception) {
                    SuaStateRepository.setOperatingMode(MokoOperatingMode.OFFLINE)
                    SuaStateRepository.updateStatus(SuaStatus.ERROR)
                    SuaStateRepository.updateServiceError(
                        "BACKGROUND SERVICE BLOCKED // Android blocked foreground mic startup (${e2.localizedMessage ?: e.localizedMessage ?: "Background restriction"}). Open the app and tap ONLINE to start."
                    )
                }
            }
        }

        fun stopService(context: Context) {
            try {
                SuaPreferences(context).setSavedOperatingMode(MokoOperatingMode.OFFLINE)
            } catch (_: Exception) {
            }
            val intent = Intent(context, SuaVoiceService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            try {
                context.stopService(intent)
            } catch (_: Exception) {
            }
            SuaVoiceSpeaker.stopSpeaking()
            SuaStateRepository.updateServiceError(null)
            SuaStateRepository.setOperatingMode(MokoOperatingMode.OFFLINE)
            SuaStateRepository.updateStatus(SuaStatus.IDLE)
            SuaStateRepository.updateLiveTranscript("OFFLINE MODE // Background voice service & microphone are completely OFF.")
        }

        fun sendCommandToService(context: Context, commandText: String) {
            val intent = Intent(context, SuaVoiceService::class.java).apply {
                action = ACTION_EXECUTE_COMMAND
                putExtra(EXTRA_COMMAND_TEXT, commandText)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var countdownTickerJob: Job? = null
    private var activeCommandJob: Job? = null

    private lateinit var prefs: SuaPreferences
    private lateinit var brain: SuaBrain
    private lateinit var controller: SuaDeviceController

    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shouldKeepListening = false
    private var isCurrentlyListening = false
    private var lastListenStartMs = 0L
    private var lastEndOfSpeechMs = 0L
    private var lastMusicLaunchTimeMs = 0L
    private var consecutiveRecognizerErrors = 0

    private fun isMusicActiveNow(): Boolean {
        val recentLaunch = (System.currentTimeMillis() - lastMusicLaunchTimeMs) in 1..20_000L
        return recentLaunch || controller.isDeviceMusicCurrentlyActive()
    }

    // Keeps Moko's voice recognizer alive when the user locks the phone screen in ONLINE or SLEEP mode
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!shouldKeepListening) return
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_USER_PRESENT,
                Intent.ACTION_SCREEN_ON -> {
                    acquireWakeLock()
                    if (!isCurrentlyListening) {
                        scheduleNextListen(if (isMusicActiveNow()) 2200L else 250L)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = SuaPreferences(this)
        controller = SuaDeviceController(this)
        brain = SuaBrain(prefs, controller)
        SuaVoiceSpeaker.init(this)
        createNotificationChannel()
        initSpeechRecognizer()

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenStateReceiver, filter)
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                // OFFLINE MODE: Completely stop background listening & release WakeLock
                shouldKeepListening = false
                activeCommandJob?.cancel()
                countdownTickerJob?.cancel()
                releaseWakeLock()
                stopListeningInternal()
                SuaVoiceSpeaker.stopSpeaking()
                SuaStateRepository.setOperatingMode(MokoOperatingMode.OFFLINE)
                SuaStateRepository.updateStatus(SuaStatus.IDLE)
                SuaStateRepository.updateLiveTranscript("")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_EXECUTE_COMMAND -> {
                val cmdText = intent.getStringExtra(EXTRA_COMMAND_TEXT).orEmpty().trim()
                if (cmdText.isNotEmpty()) {
                    processRecognizedCommand(cmdText, fromMic = false)
                }
            }

            ACTION_START_SLEEP_MODE -> {
                startBackgroundListeningInMode(MokoOperatingMode.SLEEP)
            }

            else -> {
                startBackgroundListeningInMode(MokoOperatingMode.ONLINE)
            }
        }
        return START_STICKY
    }

    private fun startBackgroundListeningInMode(mode: MokoOperatingMode) {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPerm) {
            shouldKeepListening = false
            SuaStateRepository.setOperatingMode(MokoOperatingMode.OFFLINE)
            SuaStateRepository.updateStatus(SuaStatus.ERROR)
            SuaStateRepository.updateLastReply("Microphone permission is required. Please allow microphone access.")
            stopSelf()
            return
        }

        shouldKeepListening = true
        acquireWakeLock()
        SuaStateRepository.setOperatingMode(mode)
        SuaStateRepository.markWakeWordDetected()
        startWakeCountdownTicker()

        val botName = prefs.getAssistantName()
        val userName = prefs.getUserName()

        // 1. Start foreground service FIRST so Android 14+ immediately grants foreground microphone access
        if (mode == MokoOperatingMode.SLEEP) {
            startForegroundWithNotification("Sleep Mode • Continuous Mic Active")
            SuaStateRepository.updateLastReply("Hi $userName, Sleep Mode is active. Microphone is continuously on.")
        } else {
            startForegroundWithNotification("Online Mode • Continuous Mic Active")
            SuaStateRepository.updateLastReply("Hi $userName, $botName is online and listening continuously.")
        }

        // 2. Stop any ongoing speech so AudioFocus is clear and open the microphone immediately
        SuaVoiceSpeaker.stopSpeaking()
        SuaStateRepository.updateStatus(SuaStatus.LISTENING)
        ensureSpeechRecognizer(recreate = true)
        scheduleNextListen(100L)
    }

    private fun startWakeCountdownTicker() {
        countdownTickerJob?.cancel()
        countdownTickerJob = serviceScope.launch {
            while (isActive && shouldKeepListening) {
                // Keep continuous listening window active in both ONLINE and SLEEP modes
                SuaStateRepository.markWakeWordDetected()
                SuaStateRepository.refreshWakeCountdownSeconds()

                // Permanent Continuous Mic Watchdog:
                // 1) Do NOT interrupt between onEndOfSpeech and onResults (up to 3.8s)
                // 2) If not listening and not speaking, immediately restart microphone
                // 3) If stuck for >9s without callback, recreate SpeechRecognizer and restart
                val now = System.currentTimeMillis()
                val musicActive = isMusicActiveNow()
                val idleThreshold = if (musicActive) 5500L else 1100L
                val stuckThreshold = 9000L

                if (shouldKeepListening && !SuaVoiceSpeaker.isSpeakingNow) {
                    val waitingForFinalResults = lastEndOfSpeechMs > 0L && (now - lastEndOfSpeechMs) < 3800L
                    if (!waitingForFinalResults) {
                        if (!isCurrentlyListening && (now - lastListenStartMs > idleThreshold)) {
                            scheduleNextListen(if (musicActive) 1500L else 100L)
                        } else if (isCurrentlyListening && lastEndOfSpeechMs > 0L && (now - lastEndOfSpeechMs >= 3800L)) {
                            isCurrentlyListening = false
                            lastEndOfSpeechMs = 0L
                            ensureSpeechRecognizer(recreate = true)
                            scheduleNextListen(150L)
                        } else if (isCurrentlyListening && (now - lastListenStartMs > stuckThreshold)) {
                            isCurrentlyListening = false
                            lastEndOfSpeechMs = 0L
                            ensureSpeechRecognizer(recreate = true)
                            scheduleNextListen(150L)
                        }
                    }
                }

                delay(750L)
            }
        }
    }

    private fun handleInstantStopInterruption() {
        activeCommandJob?.cancel()
        activeCommandJob = null
        lastMusicLaunchTimeMs = 0L
        SuaVoiceSpeaker.stopSpeaking()
        controller.stopAllActiveOperations()
        SuaStateRepository.markWakeWordDetected()
        val botName = prefs.getAssistantName()
        SuaStateRepository.updateStatus(SuaStatus.LISTENING)
        SuaStateRepository.updateLastReply("Stopped. I'm listening for your next command.")
        updateNotification("$botName • Listening for next command")
        if (shouldKeepListening) {
            scheduleNextListen(150L)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Moko:OnlineVoiceWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(12 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun startForegroundWithNotification(contentText: String) {
        val notification = buildNotification(contentText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            SuaStateRepository.updateServiceError(null)
        } catch (e: Exception) {
            shouldKeepListening = false
            SuaStateRepository.setOperatingMode(MokoOperatingMode.OFFLINE)
            SuaStateRepository.updateStatus(SuaStatus.ERROR)
            SuaStateRepository.updateServiceError(
                "FOREGROUND MIC SERVICE FAILED // ${e.localizedMessage ?: "OS blocked background microphone"}. Open the app and tap ONLINE to restart."
            )
            stopSelf()
        }
    }

    private fun updateNotification(contentText: String) {
        if (!shouldKeepListening) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(contentText))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sleepIntent = Intent(this, SuaVoiceService::class.java).apply {
            action = ACTION_START_SLEEP_MODE
        }
        val pendingSleep = PendingIntent.getService(
            this,
            1,
            sleepIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val offlineIntent = Intent(this, SuaVoiceService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val pendingOffline = PendingIntent.getService(
            this,
            2,
            offlineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val botName = prefs.getAssistantName()
        val modeLabel = SuaStateRepository.operatingMode.value.label
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("$botName ($modeLabel)")
            .setContentText(SuaStateRepository.formatCleanConversationReply(contentText))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingOpen)
            .addAction(
                android.R.drawable.ic_lock_idle_low_battery,
                "SLEEP MODE",
                pendingSleep
            )
            .addAction(
                android.R.drawable.ic_lock_power_off,
                "OFFLINE",
                pendingOffline
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private var useDefaultSystemLocaleFallback = false

    private fun initSpeechRecognizer() {
        ensureSpeechRecognizer(recreate = true)
    }

    private fun ensureSpeechRecognizer(recreate: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { ensureSpeechRecognizer(recreate) }
            return
        }
        if (!recreate && speechRecognizer != null) return
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(recognitionListener)
            }
        } catch (_: Exception) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext).apply {
                    setRecognitionListener(recognitionListener)
                }
            } catch (_: Exception) {
            }
        }
    }

    private var lastRmsUpdateMs = 0L
    private var lastPartialUpdateMs = 0L

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isCurrentlyListening = true
            lastEndOfSpeechMs = 0L
            lastListenStartMs = System.currentTimeMillis()
            if (!SuaVoiceSpeaker.isSpeakingNow && activeCommandJob?.isActive != true) {
                SuaStateRepository.updateStatus(SuaStatus.LISTENING)
            }
        }

        override fun onBeginningOfSpeech() {
            lastEndOfSpeechMs = 0L
            if (!SuaVoiceSpeaker.isSpeakingNow) {
                SuaStateRepository.updateStatus(SuaStatus.LISTENING)
            }
        }

        override fun onRmsChanged(rmsdB: Float) {
            val now = System.currentTimeMillis()
            if (now - lastRmsUpdateMs >= 220L) {
                lastRmsUpdateMs = now
                SuaStateRepository.updateAudioRms(rmsdB)
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // Keep isCurrentlyListening = true until onResults or onError arrives!
            lastEndOfSpeechMs = System.currentTimeMillis()
            SuaStateRepository.updateAudioRms(0f)
        }

        override fun onError(error: Int) {
            isCurrentlyListening = false
            lastEndOfSpeechMs = 0L
            SuaStateRepository.updateAudioRms(0f)
            if (!shouldKeepListening) {
                SuaStateRepository.updateStatus(SuaStatus.IDLE)
                return
            }

            acquireWakeLock()
            // error 12 = ERROR_LANGUAGE_UNAVAILABLE, 13 = ERROR_LANGUAGE_NOT_SUPPORTED
            if (error == 12 || error == 13) {
                useDefaultSystemLocaleFallback = true
            }

            val isNormalSilenceTimeout =
                error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

            if (isNormalSilenceTimeout) {
                consecutiveRecognizerErrors = 0
                val delayMs = if (isMusicActiveNow()) 2500L else 140L
                scheduleNextListen(delayMs)
            } else {
                consecutiveRecognizerErrors++
                ensureSpeechRecognizer(recreate = true)
                val recoveryDelay = when {
                    isMusicActiveNow() -> 2800L
                    consecutiveRecognizerErrors >= 3 -> 550L
                    else -> 280L
                }
                scheduleNextListen(recoveryDelay)
            }
        }

        override fun onResults(results: Bundle?) {
            isCurrentlyListening = false
            lastEndOfSpeechMs = 0L
            consecutiveRecognizerErrors = 0
            SuaStateRepository.updateAudioRms(0f)

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val spokenText = matches?.firstOrNull()?.trim().orEmpty()
            if (spokenText.isNotEmpty()) {
                processRecognizedCommand(spokenText, fromMic = true)
            } else if (shouldKeepListening) {
                scheduleNextListen(if (isMusicActiveNow()) 2500L else 140L)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val now = System.currentTimeMillis()
            if (now - lastPartialUpdateMs < 180L) return
            lastPartialUpdateMs = now
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (partial.isEmpty()) return

            val botName = prefs.getAssistantName()
            // 1. Instant "STOP" / "स्टॉप" / "रुको" command: halt everything immediately
            if (HindiTransliterator.isStopCommand(partial, botName)) {
                handleInstantStopInterruption()
                return
            }

            // 2. Acoustic Self-Echo Guard: ignore Moko's own voice from the phone speaker
            if (SuaVoiceSpeaker.isLikelySelfEcho(partial) ||
                HindiTransliterator.isAssistantSelfStatusPhrase(partial)
            ) {
                return
            }

            // 3. While Moko is speaking, only allow explicit user barge-in phrases / wake words
            if (SuaVoiceSpeaker.isSpeakingNow) {
                if (HindiTransliterator.isUserBargeInPhrase(partial, botName)) {
                    SuaVoiceSpeaker.stopSpeaking()
                    activeCommandJob?.cancel()
                    SuaStateRepository.updateStatus(SuaStatus.LISTENING)
                } else {
                    return
                }
            }

            SuaStateRepository.markWakeWordDetected()
            SuaStateRepository.updateLiveTranscript(brain.sanitizeForHud(partial))
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private val startListenRunnable = Runnable {
        if (shouldKeepListening) {
            startListeningInternal()
        }
    }

    private fun startListeningInternal() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startListeningInternal() }
            return
        }
        if (!shouldKeepListening) return
        if (SuaVoiceSpeaker.isSpeakingNow) {
            // Wait until Moko finishes her short sentence so the mic never records Moko's own voice
            scheduleNextListen(350L)
            return
        }

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            return
        }

        acquireWakeLock()
        ensureSpeechRecognizer(recreate = (speechRecognizer == null || consecutiveRecognizerErrors > 0))
        val recognizer = speechRecognizer
        if (recognizer == null) {
            ensureSpeechRecognizer(recreate = true)
            scheduleNextListen(450L)
            return
        }

        try {
            try {
                recognizer.cancel()
            } catch (_: Exception) {
            }
            lastListenStartMs = System.currentTimeMillis()
            lastEndOfSpeechMs = 0L
            val musicActive = isMusicActiveNow()
            val completeSilenceMs = if (musicActive) 4000L else 1600L
            val possibleSilenceMs = if (musicActive) 3000L else 1200L
            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                if (!useDefaultSystemLocaleFallback) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.getVoiceLocale())
                }
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    completeSilenceMs
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    possibleSilenceMs
                )
            }
            recognizer.startListening(recognizerIntent)
            isCurrentlyListening = true
            if (!SuaVoiceSpeaker.isSpeakingNow && activeCommandJob?.isActive != true) {
                SuaStateRepository.updateStatus(SuaStatus.LISTENING)
            }
        } catch (_: Exception) {
            isCurrentlyListening = false
            lastEndOfSpeechMs = 0L
            ensureSpeechRecognizer(recreate = true)
            scheduleNextListen(if (isMusicActiveNow()) 2500L else 350L)
        }
    }

    private fun stopListeningInternal() {
        mainHandler.removeCallbacks(startListenRunnable)
        mainHandler.post {
            isCurrentlyListening = false
            lastEndOfSpeechMs = 0L
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }
            SuaStateRepository.updateAudioRms(0f)
        }
    }

    private fun scheduleNextListen(delayMs: Long) {
        mainHandler.removeCallbacks(startListenRunnable)
        mainHandler.postDelayed(startListenRunnable, delayMs)
    }

    private fun processRecognizedCommand(rawSpokenText: String, fromMic: Boolean) {
        val botName = prefs.getAssistantName()
        val userName = prefs.getUserName()

        // 0. Instant "STOP" / "स्टॉप" / "रुको" command: always processed immediately!
        if (HindiTransliterator.isStopCommand(rawSpokenText, botName)) {
            SuaStateRepository.updateLiveTranscript("Stop")
            handleInstantStopInterruption()
            return
        }

        // 1. Acoustic Self-Echo Guard: reject Moko's own speaker output ("हो गया", "हो रहा है", etc.)
        if (fromMic && (SuaVoiceSpeaker.isLikelySelfEcho(rawSpokenText) ||
                HindiTransliterator.isAssistantSelfStatusPhrase(rawSpokenText))
        ) {
            if (shouldKeepListening) {
                scheduleNextListen(if (isMusicActiveNow()) 2500L else 180L)
            }
            return
        }

        // 2. Mid-Task Interruption: If Moko was already working on a previous task or speaking,
        // immediately cancel the previous task and stop speech to execute the user's NEW command!
        activeCommandJob?.cancel()
        activeCommandJob = null
        if (SuaVoiceSpeaker.isSpeakingNow) {
            SuaVoiceSpeaker.stopSpeaking()
        }

        val (hasWakeWord, strippedAfterWake) = HindiTransliterator.checkAndStripWakeWord(rawSpokenText, botName)

        // Reset the 60s continuous conversation timer
        SuaStateRepository.markWakeWordDetected()

        val effectiveCommand = if (hasWakeWord) strippedAfterWake else rawSpokenText.trim()

        // User said or tapped "Hello <Name>", "Ok <Name>", or "Hey <Name>" with no command yet -> wake up & listen!
        if (effectiveCommand.isEmpty()) {
            val mode = SuaStateRepository.operatingMode.value
            SuaStateRepository.markWakeWordDetected()
            SuaStateRepository.updateLiveTranscript(rawSpokenText.trim().ifEmpty { "Hello $botName" })
            SuaStateRepository.updateLastReply("Yes $userName, I'm listening. Tell me what you'd like me to do.")
            val wakeGreeting = if (mode == MokoOperatingMode.SLEEP) {
                "जी $userName, बोलिए क्या काम करना है?"
            } else {
                "हाँ $userName, बोलिए क्या काम करना है?"
            }
            stopListeningInternal()
            SuaVoiceSpeaker.speak(wakeGreeting) {
                SuaStateRepository.markWakeWordDetected()
                if (shouldKeepListening) {
                    scheduleNextListen(150L)
                }
            }
            return
        }

        val hudTranscript = brain.sanitizeForHud(effectiveCommand)
        SuaStateRepository.updateLiveTranscript(hudTranscript)
        SuaStateRepository.updateStatus(SuaStatus.PROCESSING)

        // Keep microphone listening while Moko processes/executes the task so the user can interrupt mid-task
        // with a new command ("यह काम छोड़कर दूसरा काम करो")!
        if (shouldKeepListening) {
            scheduleNextListen(180L)
        }

        activeCommandJob = serviceScope.launch {
            val parsed = brain.interpretCommand(effectiveCommand)
            if (!isActive) return@launch

            if (parsed.action == DeviceActionType.STOP_ACTION) {
                handleInstantStopInterruption()
                return@launch
            }

            // Special handling for PLAY_YOUTUBE: speak confirmation BEFORE launching YouTube so Moko's TTS
            // never grabs AudioFocus after YouTube starts playing!
            if (parsed.action == DeviceActionType.PLAY_YOUTUBE) {
                SuaStateRepository.markWakeWordDetected()
                val preSpeech = parsed.spokenReply.ifBlank {
                    "जी $userName, यूट्यूब पर गाना चला रही हूँ।"
                }
                stopListeningInternal()
                SuaVoiceSpeaker.speak(preSpeech) {
                    lastMusicLaunchTimeMs = System.currentTimeMillis()
                    val execResult = controller.executeCommand(parsed)
                    SuaStateRepository.updateLastReply(execResult.displayReply)
                    SuaStateRepository.addCommandLog(
                        userCommand = hudTranscript,
                        suaResponse = execResult.displayReply,
                        actionType = parsed.action,
                        actionSuccess = execResult.success,
                        prefs = prefs
                    )
                    updateNotification(execResult.displayReply)
                    SuaStateRepository.updateStatus(SuaStatus.LISTENING)
                    if (shouldKeepListening) {
                        scheduleNextListen(3500L)
                    }
                }
                return@launch
            }

            val execResult = controller.executeCommand(parsed)
            if (!isActive) return@launch

            val isSwitchingOffline = parsed.action == DeviceActionType.SWITCH_MODE &&
                parsed.target.equals("OFFLINE", ignoreCase = true)

            if (!isSwitchingOffline) {
                SuaStateRepository.markWakeWordDetected()
            }

            SuaStateRepository.updateLastReply(execResult.displayReply)
            SuaStateRepository.addCommandLog(
                userCommand = hudTranscript,
                suaResponse = execResult.displayReply,
                actionType = parsed.action,
                actionSuccess = execResult.success,
                prefs = prefs
            )
            updateNotification(execResult.displayReply)

            if (execResult.finalSpokenReply.isBlank()) {
                SuaStateRepository.updateStatus(SuaStatus.LISTENING)
                if (shouldKeepListening && !isSwitchingOffline) {
                    scheduleNextListen(if (isMusicActiveNow()) 2500L else 150L)
                }
                return@launch
            }

            // Pause mic while Moko speaks her response so she NEVER hears her own "हो गया / हो रहा है" voice,
            // and immediately re-open the mic in onComplete!
            stopListeningInternal()

            SuaVoiceSpeaker.speak(execResult.finalSpokenReply) {
                if (isSwitchingOffline || SuaStateRepository.operatingMode.value == MokoOperatingMode.OFFLINE) {
                    stopService(this@SuaVoiceService)
                } else if (shouldKeepListening) {
                    SuaStateRepository.markWakeWordDetected()
                    SuaStateRepository.updateStatus(SuaStatus.LISTENING)
                    scheduleNextListen(if (isMusicActiveNow()) 2500L else 140L)
                } else {
                    SuaStateRepository.updateStatus(SuaStatus.IDLE)
                }
            }
        }
    }

    override fun onDestroy() {
        shouldKeepListening = false
        countdownTickerJob?.cancel()
        releaseWakeLock()
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: Exception) {
        }
        SuaStateRepository.setOperatingMode(MokoOperatingMode.OFFLINE)
        SuaStateRepository.updateStatus(SuaStatus.IDLE)
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
