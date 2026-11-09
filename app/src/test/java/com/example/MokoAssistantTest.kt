package com.example

import com.example.data.DeviceActionType
import com.example.data.MokoOperatingMode
import com.example.data.SuaPreferences
import com.example.data.SuaStateRepository
import com.example.service.SuaVoiceSpeaker
import com.example.util.HindiTransliterator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MokoAssistantTest {

    @Before
    fun setUp() {
        SuaStateRepository.setOperatingMode(MokoOperatingMode.OFFLINE)
        SuaStateRepository.updateServiceError(null)
    }

    @Test
    fun cleanConversationDisplay_stripsTechnicalCodePrefixesAndSystemStatusLogs() {
        val rawReply = "APP LAUNCHER // Opened WhatsApp (com.whatsapp)."
        val cleanReply = SuaStateRepository.formatCleanConversationReply(rawReply)
        assertEquals("Opened WhatsApp.", cleanReply)
        assertFalse(cleanReply.contains("//"))
        assertFalse(cleanReply.contains("com.whatsapp"))

        val statusTranscript = "Moko ONLINE (58s) // Mic ON — Speak command or say 'Stop'..."
        assertEquals("", SuaStateRepository.formatCleanUserSpeech(statusTranscript))

        val realUserSpeech = "YouTube खोलो और एक अच्छा सा गाना बजाओ"
        assertEquals(realUserSpeech, SuaStateRepository.formatCleanUserSpeech(realUserSpeech))
    }

    @Test
    fun commandLogs_areBoundedAtMaxLimit() {
        for (i in 1..65) {
            SuaStateRepository.addCommandLog(
                userCommand = "Test Command $i",
                suaResponse = "Test Response $i",
                actionType = DeviceActionType.AI_CHAT,
                actionSuccess = true,
                prefs = null
            )
        }
        assertEquals(
            "Command logs must be strictly capped at MAX_COMMAND_LOGS (50)",
            SuaPreferences.MAX_COMMAND_LOGS,
            SuaStateRepository.commandLogs.value.size
        )
    }

    @Test
    fun selfEchoAndBargeInDetection_filtersSpeakerEcho_andAllowsUserCorrection() {
        SuaVoiceSpeaker.recordSpokenForEchoDetection("जी बॉस, मोको ऑनलाइन हो गई हूँ।")
        assertTrue(
            "Assistant signature phrase must be detected as self-echo",
            SuaVoiceSpeaker.isLikelySelfEcho("जी बॉस मोको ऑनलाइन हो गई हूँ")
        )
        assertFalse(
            "User correction must never be blocked as self-echo",
            SuaVoiceSpeaker.isLikelySelfEcho("नहीं नहीं प्राइम मिनिस्टर कौन है")
        )
        assertTrue(
            "User barge-in phrase must be detected",
            HindiTransliterator.isUserBargeInPhrase("नहीं नहीं प्राइम मिनिस्टर कौन है", "Moko")
        )
    }

    @Test
    fun hindiTransliterator_convertsDevanagariToCleanRomanEnglish() {
        val roman = HindiTransliterator.toRoman("पवन सिंह का गाना")
        assertTrue(
            "Transliterated text must be ASCII English: $roman",
            roman.all { ch: Char -> ch.code < 128 }
        )
        assertTrue(roman.contains("pavan", ignoreCase = true) || roman.contains("pawan", ignoreCase = true))
    }

    @Test
    fun developerIdentity_alwaysAttributesMrShashank() {
        assertEquals("MR. SHASHANK", SuaPreferences.DEVELOPER_NAME)
    }
}
