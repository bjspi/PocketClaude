package de.smartzone.pocketclaude.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.smartzone.pocketclaude.PocketClaudeApp
import de.smartzone.pocketclaude.R
import de.smartzone.pocketclaude.data.AppContainer
import de.smartzone.pocketclaude.data.AttachmentDto
import de.smartzone.pocketclaude.data.AttachmentRefDto
import de.smartzone.pocketclaude.data.AudioController
import de.smartzone.pocketclaude.data.ChatRepository
import de.smartzone.pocketclaude.data.ConversationDetailDto
import de.smartzone.pocketclaude.data.MessageDto
import de.smartzone.pocketclaude.data.SettingsRepository
import de.smartzone.pocketclaude.data.SkillsDto
import de.smartzone.pocketclaude.data.StreamEvent
import de.smartzone.pocketclaude.service.NotificationHelper
import de.smartzone.pocketclaude.service.StreamingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

data class PendingAttachment(
    val uri: Uri,
    val filename: String,
    val uploading: Boolean,
    val uploaded: AttachmentDto? = null,
    val error: String? = null,
)

data class ChatUiState(
    val conversationId: String,
    val title: String = "",
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val messages: List<MessageDto> = emptyList(),
    val streamingText: String = "",
    val streamingThinking: String = "",
    val isStreaming: Boolean = false,
    val isCompacting: Boolean = false,
    val totalTokens: Int = 0,
    val hasMidSummary: Boolean = false,
    val hasLongSummary: Boolean = false,
    val lastTurnCachedRead: Int = 0,
    val lastTurnCachedWrite: Int = 0,
    val pending: List<PendingAttachment> = emptyList(),
    val pinned: Boolean = false,
    // (Image-Gen-State wurde nach ui/images/ImageGenViewModel ausgelagert —
    // Bild-Generation läuft jetzt in eigenem Screen, nicht mehr im Chat.)
    // Skills für diesen Chat: effektive Werte (Override oder User-Default).
    // `skillsIsOverride=true` → dieser Chat hat eine eigene Einstellung.
    val skills: SkillsDto? = null,
    val skillsIsOverride: Boolean = false,
    // Auto-Speak-Override für diesen Chat. null = globaler Default greift,
    // true/false = Override aktiv.
    val autoSpeakOverride: Boolean? = null,
    // ───── In-Chat-Suche ─────
    // Tritt an, wenn der User im ⋮-Menü "Im Chat suchen" wählt. Der Server
    // wird NICHT befragt — wir suchen client-seitig in `messages`. Vorteil:
    // sofortige Treffer-Anzeige, kein Network-Roundtrip, keine FTS-Limits.
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    /** Message-IDs aller Treffer, in Reihenfolge des Verlaufs (alt → neu). */
    val searchMatches: List<Long> = emptyList(),
    /** Aktuell anvisierter Treffer-Index in `searchMatches`. -1 = kein Treffer. */
    val searchIndex: Int = -1,
) {
    /** Die aktuell anvisierte Message-ID, oder null wenn kein Treffer. */
    val currentMatchMessageId: Long?
        get() = searchMatches.getOrNull(searchIndex)
}

class ChatViewModel(
    private val repo: ChatRepository,
    private val cid: String,
    private val audio: AudioController,
    private val settingsRepo: SettingsRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState(conversationId = cid))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val audioState: StateFlow<AudioController.State> = audio.state

    private var streamJob: Job? = null

    init {
        refresh()
        loadSkills()
        loadAutoSpeakOverride()
    }

    private fun loadAutoSpeakOverride() = viewModelScope.launch {
        val override = settingsRepo.getAutoSpeakOverride(cid)
        _state.update { it.copy(autoSpeakOverride = override) }
    }

    /** Setzt den Auto-Speak-Override für diesen Chat.
     *  `override=null` → globaler Default greift wieder. */
    fun setAutoSpeakOverride(override: Boolean?) = viewModelScope.launch {
        settingsRepo.setAutoSpeakOverride(cid, override)
        _state.update { it.copy(autoSpeakOverride = override) }
    }

    /** Lädt die effektiven Skills für diese Konversation vom Server.
     *  Wird beim Eintritt in den Chat aufgerufen + nach jedem Set/Reset. */
    private fun loadSkills() = viewModelScope.launch {
        runCatching { repo.getConversationSkills(cid) }
            .onSuccess { resp ->
                _state.update {
                    it.copy(skills = resp.skills, skillsIsOverride = resp.isOverride)
                }
            }
    }

    /** Setzt einen Per-Chat-Override (`skills`). */
    fun setChatSkills(skills: SkillsDto) = viewModelScope.launch {
        runCatching { repo.setConversationSkills(cid, skills) }
            .onSuccess { resp ->
                _state.update {
                    it.copy(skills = resp.skills, skillsIsOverride = resp.isOverride)
                }
            }
            .onFailure { e ->
                _state.update {
                    it.copy(errorMessage = appContext.getString(R.string.error_skills_save, e.message ?: ""))
                }
            }
    }

    /** Löscht den Per-Chat-Override → User-Default greift wieder. */
    fun resetChatSkills() = viewModelScope.launch {
        runCatching { repo.setConversationSkills(cid, null) }
            .onSuccess { resp ->
                _state.update {
                    it.copy(skills = resp.skills, skillsIsOverride = resp.isOverride)
                }
            }
            .onFailure { e ->
                _state.update {
                    it.copy(errorMessage = appContext.getString(R.string.error_skills_reset, e.message ?: ""))
                }
            }
    }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        runCatching { repo.detail(cid) }
            .onSuccess { applyDetail(it) }
            .onFailure { e ->
                _state.update { it.copy(isLoading = false, errorMessage = e.message ?: e::class.java.simpleName) }
            }
    }

    private fun applyDetail(detail: ConversationDetailDto) {
        _state.update {
            it.copy(
                isLoading = false,
                title = detail.title,
                messages = detail.messages,
                totalTokens = detail.totalTokens,
                hasMidSummary = detail.hasMidSummary,
                hasLongSummary = detail.hasLongSummary,
                pinned = detail.pinned,
            )
        }
    }

    /** Setzt den Titel der aktuellen Konversation. */
    fun renameChat(newTitle: String) = viewModelScope.launch {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return@launch
        runCatching { repo.rename(cid, trimmed) }
            .onSuccess { _state.update { it.copy(title = trimmed) } }
            .onFailure { e ->
                _state.update { it.copy(errorMessage = appContext.getString(R.string.error_rename, e.message ?: "")) }
            }
    }

    /** Pin/Unpin der aktuellen Konversation. */
    fun togglePin() = viewModelScope.launch {
        val now = _state.value.pinned
        runCatching { repo.setPinned(cid, !now) }
            .onSuccess { _state.update { it.copy(pinned = !now) } }
            .onFailure { e ->
                _state.update { it.copy(errorMessage = appContext.getString(R.string.error_pin, e.message ?: "")) }
            }
    }

    /** Löscht die aktuelle Konversation. Callback wird nach erfolgreichem Löschen
     *  ausgelöst — typisch: zur Übersicht navigieren oder neuen Chat starten. */
    fun deleteChat(onDeleted: () -> Unit) = viewModelScope.launch {
        runCatching { repo.delete(cid) }
            .onSuccess {
                // Wenn dieser Chat als last_chat_cid gemerkt war, raus damit.
                runCatching { settingsRepo.setLastChatCid(null) }
                onDeleted()
            }
            .onFailure { e ->
                _state.update { it.copy(errorMessage = appContext.getString(R.string.error_delete, e.message ?: "")) }
            }
    }

    fun addPending(uri: Uri, filename: String) {
        _state.update { st ->
            st.copy(pending = st.pending + PendingAttachment(uri, filename, uploading = true))
        }
        viewModelScope.launch {
            runCatching { repo.uploadFromUri(uri) }
                .onSuccess { dto ->
                    _state.update { st ->
                        st.copy(pending = st.pending.map { p ->
                            if (p.uri == uri) p.copy(uploading = false, uploaded = dto) else p
                        })
                    }
                }
                .onFailure { e ->
                    _state.update { st ->
                        st.copy(pending = st.pending.map { p ->
                            if (p.uri == uri) p.copy(uploading = false, error = e.message ?: appContext.getString(R.string.error_upload)) else p
                        })
                    }
                }
        }
    }

    fun removePending(uri: Uri) {
        _state.update { st -> st.copy(pending = st.pending.filter { it.uri != uri }) }
    }

    fun send(content: String) {
        val text = content.trim()
        val pending = _state.value.pending
        if (text.isEmpty() && pending.none { it.uploaded != null }) return
        if (_state.value.isStreaming) return

        val attachmentIds = pending.mapNotNull { it.uploaded?.id }
        val attachmentRefs = pending.mapNotNull { p ->
            p.uploaded?.let {
                AttachmentRefDto(it.id, it.filename, it.mimeType, it.sizeBytes)
            }
        }

        // Optimistic user message
        val optimistic = MessageDto(
            id = -System.currentTimeMillis(),
            conversationId = cid,
            role = "user",
            content = text,
            createdAt = OffsetDateTime.now().toString(),
            tokens = 0,
            attachments = attachmentRefs,
        )
        _state.update {
            it.copy(
                messages = it.messages + optimistic,
                pending = emptyList(),
                streamingText = "",
                isStreaming = true,
            )
        }

        // Foreground-Service starten — hält den Prozess am Leben, falls der
        // User die App in den Hintergrund schiebt, während Claude streamt.
        runCatching {
            StreamingService.start(appContext, _state.value.title.ifBlank { null }, cid)
        }

        streamJob = viewModelScope.launch {
            val s = settingsRepo.current()
            repo.stream(
                cid,
                text,
                attachmentIds,
                effort = s.effort,
                systemPrompt = s.resolvedSystemPrompt,
                // Server startet nach Done eine Pre-Generation für die
                // gewählte Voice/Speed. Nächster Vorlesen-Tap = Cache-Hit.
                ttsVoice = s.ttsVoice,
                ttsRate = s.ttsSpeed,
            ).collect { ev -> handleEvent(ev) }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        runCatching { StreamingService.stop(appContext) }
        finalizeStreaming()
    }

    private fun handleEvent(ev: StreamEvent) {
        when (ev) {
            is StreamEvent.TitleUpdated -> {
                _state.update { it.copy(title = ev.newTitle.ifBlank { it.title }) }
            }

            is StreamEvent.UserSaved -> {
                // Update optimistic user message ID to the real one
                _state.update { st ->
                    val msgs = st.messages.toMutableList()
                    val idx = msgs.indexOfLast { it.role == "user" && it.id < 0 }
                    if (idx >= 0) {
                        msgs[idx] = msgs[idx].copy(id = ev.userMessageId)
                    }
                    st.copy(messages = msgs)
                }
            }

            StreamEvent.CompactionStarted -> _state.update { it.copy(isCompacting = true) }
            StreamEvent.CompactionDone -> _state.update {
                it.copy(isCompacting = false, hasMidSummary = true)
            }

            is StreamEvent.Delta -> _state.update {
                it.copy(streamingText = it.streamingText + ev.text)
            }

            is StreamEvent.ThinkingDelta -> _state.update {
                it.copy(streamingThinking = it.streamingThinking + ev.text)
            }

            StreamEvent.BlockStop -> {
                // Block-Stop kommt nach jedem content_block (Thinking oder Text).
                // Wir machen nichts speziell — die State-Updates geben das UI
                // genug Information.
            }

            is StreamEvent.Done -> {
                val finalText = _state.value.streamingText
                val totalThisTurn = ev.tokensIn + ev.tokensOut + ev.tokensCachedRead + ev.tokensCachedWrite
                _state.update { st ->
                    val newMsg = MessageDto(
                        id = ev.assistantMessageId,
                        conversationId = cid,
                        role = "assistant",
                        content = finalText,
                        createdAt = OffsetDateTime.now().toString(),
                        tokens = totalThisTurn,
                    )
                    st.copy(
                        messages = st.messages + newMsg,
                        streamingText = "",
                        streamingThinking = "",
                        isStreaming = false,
                        totalTokens = totalThisTurn,
                        lastTurnCachedRead = ev.tokensCachedRead,
                        lastTurnCachedWrite = ev.tokensCachedWrite,
                    )
                }
                // Foreground-Service stoppen
                runCatching { StreamingService.stop(appContext) }
                // App im Hintergrund? → Notification posten
                postResultNotificationIfBackground(finalText)
                // Auto-Speak: per-Chat-Override (falls gesetzt) hat Vorrang,
                // sonst globaler ttsAutoSpeak.
                viewModelScope.launch {
                    val s = settingsRepo.current()
                    val override = _state.value.autoSpeakOverride
                    val shouldSpeak = override ?: s.ttsAutoSpeak
                    if (shouldSpeak) {
                        speak(ev.assistantMessageId)
                    }
                }
            }

            is StreamEvent.ErrorEvent -> {
                _state.update {
                    it.copy(
                        isStreaming = false,
                        streamingText = "",
                        streamingThinking = "",
                        errorMessage = ev.message,
                    )
                }
                runCatching { StreamingService.stop(appContext) }
            }
        }
    }

    /** Postet die "Antwort fertig"-Notification, falls die App gerade im Hintergrund ist. */
    private fun postResultNotificationIfBackground(replyText: String) {
        val app = appContext.applicationContext as? PocketClaudeApp ?: return
        if (app.isInForeground) return
        runCatching {
            NotificationHelper.showResultNotification(
                context = appContext,
                conversationTitle = _state.value.title.ifBlank { appContext.getString(R.string.app_name) },
                conversationId = cid,
                snippet = replyText,
            )
        }
    }

    private fun finalizeStreaming() {
        val partial = _state.value.streamingText
        if (partial.isNotEmpty()) {
            _state.update { st ->
                val newMsg = MessageDto(
                    id = -System.currentTimeMillis(),
                    conversationId = cid,
                    role = "assistant",
                    content = partial + "\n\n_" + appContext.getString(R.string.generated_chat_aborted) + "_",
                    createdAt = OffsetDateTime.now().toString(),
                )
                st.copy(
                    messages = st.messages + newMsg,
                    streamingText = "",
                    isStreaming = false,
                )
            }
        } else {
            _state.update { it.copy(isStreaming = false, streamingText = "") }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    // ===================== In-Chat-Suche =====================

    fun openSearch() = _state.update { it.copy(searchActive = true) }

    fun closeSearch() = _state.update {
        it.copy(
            searchActive = false,
            searchQuery = "",
            searchMatches = emptyList(),
            searchIndex = -1,
        )
    }

    /** Aktualisiert den Suchbegriff und berechnet die Treffer neu.
     *  Sucht case-insensitive in `content` aller Messages. Erster Treffer wird
     *  zu searchIndex=0 — der ChatScreen springt dann via LaunchedEffect dahin. */
    fun setSearchQuery(query: String) = _state.update { st ->
        val q = query.trim()
        if (q.isEmpty()) {
            st.copy(searchQuery = query, searchMatches = emptyList(), searchIndex = -1)
        } else {
            val matches = st.messages
                .asSequence()
                .filter { it.content.contains(q, ignoreCase = true) }
                .map { it.id }
                .toList()
            st.copy(
                searchQuery = query,
                searchMatches = matches,
                searchIndex = if (matches.isEmpty()) -1 else 0,
            )
        }
    }

    fun nextMatch() = _state.update { st ->
        if (st.searchMatches.isEmpty()) st
        else st.copy(searchIndex = (st.searchIndex + 1) % st.searchMatches.size)
    }

    fun previousMatch() = _state.update { st ->
        if (st.searchMatches.isEmpty()) st
        else st.copy(
            searchIndex = (st.searchIndex - 1 + st.searchMatches.size) % st.searchMatches.size
        )
    }

    fun speak(messageId: Long) = viewModelScope.launch {
        val s = settingsRepo.current()
        try {
            val url = repo.audioUrl(messageId, s.ttsVoice, s.ttsSpeed)
            val cacheKey = "audio-$messageId-${s.ttsVoice}-${s.ttsSpeed}"
            audio.play(messageId, url, cacheKey)
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = appContext.getString(R.string.error_speak, e.message ?: "")) }
        }
    }

    fun stopSpeaking() {
        audio.stop()
    }

    fun pauseSpeaking() {
        audio.pause()
    }

    fun resumeSpeaking() {
        audio.resume()
    }

    fun clearAudioError() {
        audio.clearError()
    }

    /** Lädt den Markdown-Export der Konversation. Callback bekommt Markdown-Text. */
    fun exportMarkdown(onReady: (String, String) -> Unit) = viewModelScope.launch {
        runCatching { repo.exportMarkdown(cid) }
            .onSuccess { md ->
                val title = _state.value.title.ifBlank { appContext.getString(R.string.generated_chat_default_title) }
                onReady(title, md)
            }
            .onFailure { e ->
                _state.update { it.copy(errorMessage = appContext.getString(R.string.error_export, e.message ?: "")) }
            }
    }

    override fun onCleared() {
        super.onCleared()
        streamJob?.cancel()
        audio.stop()
        // Service stoppen, falls noch aktiv (z.B. wenn der User aus dem Chat
        // navigiert ohne abzuwarten — die Notification soll dann verschwinden).
        if (_state.value.isStreaming) {
            runCatching { StreamingService.stop(appContext) }
        }
    }

    // ===================== Image Generation (Gemini) =====================
    // Image-Gen ist 2026-05-19 in einen eigenen Screen migriert worden
    // (ui/images/ImageGenViewModel + ImagesScreen). Die Methoden hier sind
    // tot, lassen wir aber als private fun bestehen wäre verwirrend — also
    // raus. Falls noch ein Aufrufer existiert: Compile-Fehler ist gewollt.

    companion object {
        fun factory(container: AppContainer, cid: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ChatViewModel(
                        repo = container.chatRepository,
                        cid = cid,
                        audio = container.audioController,
                        settingsRepo = container.settingsRepository,
                        appContext = container.appContext,
                    ) as T
                }
            }
    }
}
