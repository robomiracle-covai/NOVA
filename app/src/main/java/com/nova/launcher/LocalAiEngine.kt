package com.nova.launcher

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalAiEngine(private val context: Context) {
    private val TAG = "LocalAiEngine"
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var generateJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    interface AiCallback {
        fun onChunk(text: String)
        fun onDone()
        fun onError(error: String)
    }

    fun initialize(modelPath: String, useGpu: Boolean, callback: AiCallback) {
        scope.launch {
            try {
                Log.d(TAG, "Initializing LiteRT-LM Engine with model: $modelPath (GPU=$useGpu)")
                
                // MTP (Multi-Token Prediction) is recommended for GPU backends
                if (useGpu) {
                    @OptIn(ExperimentalApi::class)
                    ExperimentalFlags.enableSpeculativeDecoding = true
                }
                
                val backend = if (useGpu) {
                    Backend.GPU()
                } else {
                    Backend.CPU()
                }

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    cacheDir = context.cacheDir.path
                )
                
                engine?.close()
                engine = Engine(engineConfig)
                engine?.initialize()
                
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of("You are a helpful assistant."),
                    samplerConfig = SamplerConfig(temperature = 0.8, topK = 10, topP = 0.95)
                )
                
                conversation = engine?.createConversation(conversationConfig)
                Log.d(TAG, "LiteRT-LM Engine Initialized Successfully.")
                
                withContext(Dispatchers.Main) {
                    callback.onDone()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize LiteRT-LM", e)
                withContext(Dispatchers.Main) {
                    callback.onError(e.message ?: "Unknown error during initialization")
                }
            }
        }
    }

    fun updateSystemInstruction(instruction: String) {
        val currentEngine = engine ?: return
        try {
            conversation?.close()
            val config = ConversationConfig(
                systemInstruction = Contents.of(instruction),
                samplerConfig = SamplerConfig(temperature = 0.8, topK = 10, topP = 0.95)
            )
            conversation = currentEngine.createConversation(config)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update system instruction", e)
        }
    }

    fun generateStream(prompt: String, callback: AiCallback) {
        val currentConversation = conversation
        if (currentConversation == null) {
            callback.onError("Conversation is not initialized.")
            return
        }

        generateJob?.cancel()
        generateJob = scope.launch {
            try {
                currentConversation.sendMessageAsync(prompt).collect { chunk ->
                    withContext(Dispatchers.Main) {
                        callback.onChunk(chunk.toString())
                    }
                }
                withContext(Dispatchers.Main) {
                    callback.onDone()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled manually
                Log.d(TAG, "Generation cancelled.")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onError(e.message ?: "Exception sending message")
                }
            }
        }
    }

    fun cancelGeneration() {
        generateJob?.cancel()
    }

    fun close() {
        conversation?.close()
        engine?.close()
    }
}
