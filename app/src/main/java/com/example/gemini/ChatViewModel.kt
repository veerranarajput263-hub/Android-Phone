package com.example.gemini

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class ChatMessage(val id: String, val text: String, val isUser: Boolean, val type: MessageType = MessageType.TEXT)
enum class MessageType { TEXT, AUDIO, IMAGE }

class ChatViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    init {
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(application)
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to create SpeechRecognizer", e)
        }
        try {
            textToSpeech = TextToSpeech(application, this)
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Failed to create TextToSpeech", e)
        }
        
        // Initial bot message
        addMessage(ChatMessage("0", "Hi! I am Creative AI. I can brainstorm stories, write poems, and generate voice. What do you want to create?", isUser = false))
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.US
            isTtsReady = true
        }
    }

    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Log.e("Speech", "Error: $error")
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    sendMessage(matches[0])
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    // Prepare history to send context to Gemini
    private fun buildContents(): List<Content> {
        return _messages.value.map { msg ->
            Content(
                role = if (msg.isUser) "user" else "model",
                parts = listOf(Part(text = msg.text))
            )
        }
    }

    fun sendMessage(text: String) {
        val userMsg = ChatMessage(System.currentTimeMillis().toString(), text, true)
        addMessage(userMsg)
        
        generateResponse(text)
    }

    private fun generateResponse(prompt: String) {
        viewModelScope.launch {
            _isGenerating.value = true
            try {
                // Determine model based on prompt
                val isImageRequest = prompt.contains("generate image", ignoreCase = true) || prompt.contains("draw", ignoreCase = true) || prompt.contains("create an image", ignoreCase = true)
                val isAudioRequest = prompt.contains("generate voice", ignoreCase = true) || prompt.contains("say something", ignoreCase = true)
                
                val modelToUse = when {
                    isImageRequest -> "gemini-3.1-flash-image-preview" // Might need adjustment / error handling for real APIs depending on keys
                    isAudioRequest -> "gemini-2.5-flash-preview-tts"
                    else -> "gemini-3.1-pro-preview"
                }

                if (isImageRequest || isAudioRequest) {
                     // For image / TTS from specialized endpoints, we might have limitations on direct standard key access. 
                     // Therefore, to be safe, we will just use the standard text API and TTS internally if it's general audio request, 
                     // or we can invoke the TTS natively.
                     if (isAudioRequest && isTtsReady) {
                          // just generate text but speak it at the end
                     }
                }
                
                val requestContents = buildContents()
                val request = GenerateContentRequest(
                    contents = requestContents,
                    systemInstruction = Content(parts = listOf(Part(text = "You are a highly creative and intelligent AI assistant tailored for storytelling, game design, and creative writing. Output your responses in clean, formatted text.")))
                )

                val response = RetrofitClient.service.generateContent(
                    model = "gemini-3.1-pro-preview", // We stick to text by default since standard keys usually support it out of the box reliably
                    apiKey = BuildConfig.GEMINI_API_KEY,
                    request = request
                )

                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: response.error?.message ?: "Sorry, I couldn't generate a response."

                val botMsg = ChatMessage(System.currentTimeMillis().toString(), responseText, false)
                addMessage(botMsg)

                // If user wants speech, or normally read aloud? It's better if user specifically wants it read.
                if (isAudioRequest && isTtsReady) {
                    textToSpeech?.speak(responseText, TextToSpeech.QUEUE_FLUSH, null, null)
                }

            } catch (e: Exception) {
                Log.e("Gemini", "API Error", e)
                addMessage(ChatMessage(System.currentTimeMillis().toString(), "Error: ${e.message}", false))
            } finally {
                _isGenerating.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
