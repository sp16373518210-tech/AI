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
    private var lastMusicLaunchTimeMs = 0L

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
                        scheduleNextListen(if (isMusicActiveNow()) 2500L else 350L)
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
                SuaStateRepository.updateLiveTranscript("OFFLINE MODE // Background listening is completely OFF.")
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
            SuaStateRepository.updateLastReply("MIC PERMISSION REQUIRED // Grant Microphone permission in Side Menu -> Settings.")
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
            startForegroundWithNotification("Sleep Mode • Listening for 'Hi $botName'")
            SuaStateRepository.updateLastReply("Hi $userName, Sleep Mode is active. Tell me who to call or what phone setting to change.")
        } else {
            startForegroundWithNotification("Online Mode • Listening for your voice")
            SuaStateRepository.updateLastReply("Hi $userName, I'm online and listening. Go ahead and speak anytime.")
        }

        // 2. Stop any ongoing speech so AudioFocus is clear and open the microphone immediately
        SuaVoiceSpeaker.stopSpeaking()
        SuaStateRepository.updateStatus(SuaStatus.LISTENING)
        ensureSpeechRecognizer(recreate = true)
        scheduleNextListen(120L)
    }

    private fun startWakeCountdownTicker() {
        countdownTickerJob?.cancel()
        countdownTickerJob = serviceScope.launch {
            var wasInActiveSession = true
            while (isActive && shouldKeepListening) {
                // Keep the 60-second timer paused/full while Moko is speaking, processing, or waiting on Call/SMS confirmation
                if (SuaVoiceSpeaker.isSpeakingNow ||
                    activeCommandJob?.isActive == true ||
                    SuaStateRepository.pendingComms.value != null
                ) {
                    SuaStateRepository.markWakeWordDetected()
                }
                val remainingSec = SuaStateRepository.refreshWakeCountdownSeconds()
                if (remainingSec > 0) {
                    wasInActiveSession = true
                } else if (wasInActiveSession) {
                    wasInActiveSession = false
                    val botName = prefs.getAssistantName()
                    val modeLabel = SuaStateRepository.operatingMode.value.label
                    updateNotification("Standby ($modeLabel) • Say 'Hi $botName' to wake")
                }

                // Permanent Mic Watchdog: restart recognizer if idle OR if stuck for >8.5s without callback
                val now = System.currentTimeMillis()
                val musicActive = isMusicActiveNow()
                val idleThreshold = if (musicActive) 6500L else 1800L
                val stuckThreshold = 8500L

                if (shouldKeepListening && !SuaVoiceSpeaker.isSpeakingNow && activeCommandJob?.isActive != true) {
                    if (!isCurrentlyListening && (now - lastListenStartMs > idleThreshold)) {
                        scheduleNextListen(if (musicActive) 1800L else 120L)
                    } else if (isCurrentlyListening && (now - lastListenStartMs > stuckThreshold)) {
                        isCurrentlyListening = false
                        ensureSpeechRecognizer(recreate = true)
                        scheduleNextListen(180L)
                    }
                }

                delay(1000L)
            }
        }
    }

    private fun handleInstantStopInterruption() {
        activeCommandJob?.cancel()
        activeCommandJob = null
        lastMusicLaunchTimeMs = 0L
        controller.stopAllActiveOperations()
        SuaStateRepository.markWakeWordDetected()
        val botName = prefs.getAssistantName()
        SuaStateRepository.updateStatus(SuaStatus.LISTENING)
        SuaStateRepository.updateLastReply("Stopped. I'm listening for your next command.")
        updateNotification("$botName • Listening for next command")
        if (shouldKeepListening) {
            scheduleNextListen(180L)
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
                // Hold wake lock for up to 12 hours per Online/Sleep session so screen-lock doesn't suspend CPU
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
            .setContentText(contentText)
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
            lastListenStartMs = System.currentTimeMillis()
            if (!SuaVoiceSpeaker.isSpeakingNow && activeCommandJob?.isActive != true) {
                SuaStateRepository.updateStatus(SuaStatus.LISTENING)
            }
        }

        override fun onBeginningOfSpeech() {
            if (!SuaVoiceSpeaker.isSpeakingNow) {
                SuaStateRepository.updateStatus(SuaStatus.LISTENING)
            }
        }

        override fun onRmsChanged(rmsdB: Float) {
            val now = System.currentTimeMillis()
            if (now - lastRmsUpdateMs >= 260L) {
                lastRmsUpdateMs = now
                SuaStateRepository.updateAudioRms(rmsdB)
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isCurrentlyListening = false
            SuaStateRepository.updateAudioRms(0f)
        }

        override fun onError(error: Int) {
            isCurrentlyListening = false
            SuaStateRepository.updateAudioRms(0f)
            if (shouldKeepListening) {
                acquireWakeLock()
                // error 12 = ERROR_LANGUAGE_UNAVAILABLE, 13 = ERROR_LANGUAGE_NOT_SUPPORTED
                if (error == 12 || error == 13) {
                    useDefaultSystemLocaleFallback = true
                }
                val musicActive = isMusicActiveNow()
                val inActiveSession = SuaStateRepository.isWithinWakeConversationWindow()
                val baseDelay = when {
                    musicActive -> 3500L
                    inActiveSession -> 240L
                    prefs.isLowBatteryWakeMode() -> 650L
                    else -> 380L
                }
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                ) {
                    ensureSpeechRecognizer(recreate = true)
                    scheduleNextListen(baseDelay + 200L)
                } else {
                    scheduleNextListen(baseDelay)
                }
            } else {
                SuaStateRepository.updateStatus(SuaStatus.IDLE)
            }
        }

        override fun onResults(results: Bundle?) {
            isCurrentlyListening = false
            SuaStateRepository.updateAudioRms(0f)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val spokenText = matches?.firstOrNull()?.trim().orEmpty()
            if (spokenText.isNotEmpty()) {
                processRecognizedCommand(spokenText, fromMic = true)
            } else if (shouldKeepListening) {
                val nextDelay = when {
                    isMusicActiveNow() -> 3200L
                    SuaStateRepository.isWithinWakeConversationWindow() -> 200L
                    else -> 420L
                }
                scheduleNextListen(nextDelay)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val now = System.currentTimeMillis()
            if (now - lastPartialUpdateMs < 200L) return
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
            if (SuaVoiceSpeaker.isLikelySelfEcho(partial)) {
                return
            }

            // 3. Natural Mid-Sentence Barge-In: if Moko is speaking and the user speaks a new question or correction
            val wordCount = partial.split(Regex("\\s+")).count { it.length >= 2 }
            if (SuaVoiceSpeaker.isSpeakingNow &&
                (HindiTransliterator.isUserBargeInPhrase(partial, botName) || wordCount >= 2)
            ) {
                SuaVoiceSpeaker.stopSpeaking()
                SuaStateRepository.updateStatus(SuaStatus.LISTENING)
            }

            if (SuaStateRepository.isWithinWakeConversationWindow()) {
                // Actively speaking keeps the 60s conversation session alive
                SuaStateRepository.markWakeWordDetected()
            }
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

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            return
        }

        acquireWakeLock()
        ensureSpeechRecognizer(recreate = false)
        val recognizer = speechRecognizer
        if (recognizer == null) {
            ensureSpeechRecognizer(recreate = true)
            scheduleNextListen(600L)
            return
        }

        try {
            lastListenStartMs = System.currentTimeMillis()
            val musicActive = isMusicActiveNow()
            val completeSilenceMs = if (musicActive) 4500L else 1800L
            val possibleSilenceMs = if (musicActive) 3500L else 1400L
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
            ensureSpeechRecognizer(recreate = true)
            scheduleNextListen(if (isMusicActiveNow()) 3200L else 500L)
        }
    }

    private fun stopListeningInternal() {
        mainHandler.removeCallbacks(startListenRunnable)
        mainHandler.post {
            isCurrentlyListening = false
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

        // 0. Instant "STOP" / "स्टॉप" / "रुको" command: always processed immediately, even while Moko is speaking or working!
        if (HindiTransliterator.isStopCommand(rawSpokenText, botName)) {
            SuaStateRepository.updateLiveTranscript("Stop")
            handleInstantStopInterruption()
            return
        }

        // 1. Acoustic Self-Echo Guard: reject Moko's own speaker output, while allowing genuine user barge-in!
        if (fromMic && SuaVoiceSpeaker.isLikelySelfEcho(rawSpokenText)) {
            if (shouldKeepListening) {
                scheduleNextListen(if (isMusicActiveNow()) 3200L else 240L)
            }
            return
        }

        // 2. If the user spoke a genuine command/correction while Moko was speaking, cut off Moko's current speech immediately!
        if (SuaVoiceSpeaker.isSpeakingNow) {
            SuaVoiceSpeaker.stopSpeaking()
        }

        val (hasWakeWord, strippedAfterWake) = HindiTransliterator.checkAndStripWakeWord(rawSpokenText, botName)
        val inActiveWindow = SuaStateRepository.isWithinWakeConversationWindow() ||
            SuaStateRepository.pendingComms.value != null

        // When 60 seconds of silence have elapsed, Moko is in Hands-Free Standby and requires "Hi Moko" / "Ok Moko"
        if (fromMic && !hasWakeWord && !inActiveWindow) {
            if (shouldKeepListening) {
                val standbyDelay = when {
                    isMusicActiveNow() -> 3500L
                    prefs.isLowBatteryWakeMode() -> 550L
                    else -> 250L
                }
                scheduleNextListen(standbyDelay)
            }
            return
        }

        // Reset the 1-minute (60s) continuous conversation timer!
        SuaStateRepository.markWakeWordDetected()

        val effectiveCommand = if (hasWakeWord) strippedAfterWake else rawSpokenText.trim()

        // User said "Ok Moko" / "Hi Moko" with no command yet -> wake up & open 60-second continuous conversation!
        if (effectiveCommand.isEmpty()) {
            val mode = SuaStateRepository.operatingMode.value
            SuaStateRepository.markWakeWordDetected()
            SuaStateRepository.updateLiveTranscript("Hi $botName")
            SuaStateRepository.updateLastReply("Yes $userName, I'm listening. Tell me what you'd like me to do.")
            val wakeGreeting = if (mode == MokoOperatingMode.SLEEP) {
                "जी $userName, बोलिए क्या काम करना है?"
            } else {
                "हाँ मेरे प्यारे $userName, बोलिए क्या काम करना है?"
            }
            SuaVoiceSpeaker.speak(wakeGreeting) {
                SuaStateRepository.markWakeWordDetected()
                if (shouldKeepListening) {
                    scheduleNextListen(180L)
                }
            }
            return
        }

        val hudTranscript = brain.sanitizeForHud(effectiveCommand)
        SuaStateRepository.updateLiveTranscript(hudTranscript)
        SuaStateRepository.updateStatus(SuaStatus.PROCESSING)

        activeCommandJob?.cancel()
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
                        // Give YouTube 4.5s to load and start playing before re-arming the microphone
                        scheduleNextListen(4500L)
                    }
                }
                return@launch
            }

            val execResult = controller.executeCommand(parsed)
            if (!isActive) return@launch

            // Keep 60-second window open unless switching to OFFLINE mode
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

            // If finalSpokenReply is blank (e.g. adjusting music volume while a song is playing),
            // do NOT speak so the user's music continues uninterrupted!
            if (execResult.finalSpokenReply.isBlank()) {
                SuaStateRepository.updateStatus(SuaStatus.LISTENING)
                if (shouldKeepListening && !isSwitchingOffline) {
                    scheduleNextListen(if (isMusicActiveNow()) 3200L else 280L)
                }
                return@launch
            }

            // Ensure mic is actively listening while Moko speaks so the user can interrupt or say "Stop" anytime!
            if (shouldKeepListening && !isSwitchingOffline) {
                scheduleNextListen(280L)
            }

            SuaVoiceSpeaker.speak(execResult.finalSpokenReply) {
                if (isSwitchingOffline || SuaStateRepository.operatingMode.value == MokoOperatingMode.OFFLINE) {
                    stopService(this@SuaVoiceService)
                } else if (shouldKeepListening) {
                    // Refresh 60s timer right after Moko finishes speaking so the user has a full 1 minute to reply!
                    SuaStateRepository.markWakeWordDetected()
                    scheduleNextListen(if (isMusicActiveNow()) 3200L else 220L)
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
