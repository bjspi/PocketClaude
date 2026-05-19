package de.smartzone.pocketclaude.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.font.FontWeight as FW
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import android.app.Activity
import de.smartzone.pocketclaude.data.BillingStatusDto
import de.smartzone.pocketclaude.data.LocalePrefs
import de.smartzone.pocketclaude.data.SystemPromptMode
import de.smartzone.pocketclaude.data.ThemeMode
import de.smartzone.pocketclaude.ui.components.InfoBulletParagraph
import de.smartzone.pocketclaude.ui.components.InfoButton
import de.smartzone.pocketclaude.ui.components.InfoParagraph
import de.smartzone.pocketclaude.ui.theme.PocketTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    val testResult by vm.testResult.collectAsState()
    // ttsStatus auf Top-Level der Settings — wird vom Quick-Setup-Banner gelesen
    // (Edge-Provider ist „ready" auch ohne Setup), und von TtsSection selbst
    // weiter intern wieder collected. Doppelte Subscriptions sind in Compose
    // billig (StateFlow shared).
    val ttsStatus by vm.ttsStatus.collectAsState()
    // (showToken früher hier — Token-Feld ist entfallen; Username/PW ersetzen es)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ─── Quick-Setup-Banner — nur sichtbar bis alles eingerichtet ───
            val hasProfile = settings.activeProfile != null
            val isLoggedIn = settings.serverToken.isNotBlank()
            val ttsReady = (ttsStatus?.configured == true) ||
                (ttsStatus?.provider == "edge_tts")  // Edge ist immer ready
            if (!hasProfile || !isLoggedIn) {
                QuickSetupBanner(
                    hasProfile = hasProfile,
                    isLoggedIn = isLoggedIn,
                    ttsReady = ttsReady,
                )
            }

            // Forced-PW-Change-Dialog beobachtet den Login-State global.
            ForcedPasswordChangeWatcher(vm)

            // ═════════════════════════════════════════════════════════════
            //  1. PROFIL & SERVER — immer sichtbar, oberste Priorität.
            // ═════════════════════════════════════════════════════════════
            ProfileAndServerCard(
                vm = vm,
                settings = settings,
                testResult = testResult,
            )

            // ═════════════════════════════════════════════════════════════
            //  2. CLAUDE — Denktiefe + System-Prompt + Skills,
            //     alles was Claudes Verhalten direkt steuert.
            // ═════════════════════════════════════════════════════════════
            ClaudeBehaviorCard(vm = vm, settings = settings)

            // ═════════════════════════════════════════════════════════════
            //  3-7. Sekundäre Sektionen — alle als ExpandableSection für
            //  konsistentes Layout. Default collapsed, der User klappt
            //  nur das auf, was er gerade braucht.
            // ═════════════════════════════════════════════════════════════

            ExpandableSection(
                title = "Vorlesen",
                subtitle = "Provider, Stimme, Auto-Vorlesen",
                initiallyExpanded = false,
                infoTitle = "Sprachausgabe — drei Setup-Pfade",
                infoBody = {
                    InfoParagraph(
                        "Pocket Claude kann Claude-Antworten vorlesen lassen. Drei Provider " +
                            "stehen zur Auswahl — von \"Zero-Setup\" bis \"Premium-Voice-Qualität\"."
                    )
                    InfoBulletParagraph(
                        "Edge (Default, kein Setup):",
                        "Microsoft-Edge-Vorlesestimmen. Komplett gratis, ohne API-Key oder " +
                            "Service-Account. Klangqualität ist mechanischer als Gemini/Chirp, " +
                            "aber perfekt um TTS sofort auszuprobieren."
                    )
                    InfoBulletParagraph(
                        "Gemini API (mit AI-Studio-Key):",
                        "Free Tier: 10 Calls/Tag pro Key — mit 1 Key knapp, mit Multi-Key-Pool " +
                            "gut. Paid: $0,50/M Input + $10/M Audio-Output. Beste Modell-Inferenz."
                    )
                    InfoBulletParagraph(
                        "Cloud TTS (mit Service-Account):",
                        "1 Mio Zeichen/Monat KOSTENLOS bei Chirp 3 HD (≈ 20 h Audio). Beste " +
                            "Voice-Qualität. Setup komplexer (Cloud-Projekt + Service-Account-JSON)."
                    )
                },
            ) {
                TtsSection(vm = vm, settings = settings)
            }

            ExpandableSection(
                title = "Bilder generieren",
                subtitle = "Gemini Nano Banana — Text-zu-Bild + Editing",
                initiallyExpanded = false,
                infoTitle = "Bild-Generierung einrichten",
                infoBody = {
                    InfoParagraph(
                        "Für die Bild-Generierung brauchst Du einen API-Key von Google AI " +
                            "Studio. Der wird pro Profil server-seitig gespeichert und kann " +
                            "optional auch für die Gemini-Sprachausgabe mitverwendet werden."
                    )
                    InfoBulletParagraph("1.", "https://aistudio.google.com → mit Google-Konto anmelden.")
                    InfoBulletParagraph(
                        "2.", "Links \"API keys\" → \"Create API key\". Key beginnt mit \"AIza…\"."
                    )
                    InfoBulletParagraph("3.", "Key hier ins Feld unten kopieren.")
                    InfoBulletParagraph(
                        "Free Tier:",
                        "Möglich ohne Kreditkarte (bei der Key-Erstellung „Create in new project\" " +
                            "wählen). 15 Calls/Min, 1.500 Calls/Tag."
                    )
                    InfoBulletParagraph(
                        "Paid Tier:",
                        "Höhere Rate-Limits, kein Training. Nano-Banana ca. \$0.04/Bild."
                    )
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                    ) {
                        Text(
                            "💡 Derselbe Key funktioniert auch für die Gemini-TTS-Sprachausgabe " +
                                "(Sektion „Vorlesen\"). Wenn dort kein separater Key eingetragen " +
                                "ist, nutzt der Server automatisch diesen.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                    ImageGenSection(vm = vm)
                }
            }

            ExpandableSection(
                title = stringResource(de.smartzone.pocketclaude.R.string.section_appearance),
                subtitle = when (settings.themeMode) {
                    ThemeMode.SYSTEM -> "Theme: System"
                    ThemeMode.LIGHT -> "Theme: Hell"
                    ThemeMode.DARK -> "Theme: Dunkel"
                },
                initiallyExpanded = false,
            ) {
                ThemeCard(settings = settings, vm = vm)
                Spacer(Modifier.height(12.dp))
                LanguageCard()
            }

            ExpandableSection(
                title = "Daten sichern",
                subtitle = "Chats + Einstellungen exportieren/importieren",
                initiallyExpanded = false,
                infoTitle = "Zwei verschiedene Backup-Typen",
                infoBody = {
                    InfoBulletParagraph(
                        "Chat-Backup:",
                        "Alle Konversationen + Anhänge als verschlüsseltes ZIP (AES-256). " +
                            "Beim Import: \"Ersetzen\" überschreibt, \"Zusammenführen\" kombiniert."
                    )
                    InfoBulletParagraph(
                        "Einstellungs-Backup:",
                        "TTS-Provider, der komplette API-Key-Pool, Image-API-Key, Skills, " +
                            "Voice, Theme, Effort, System-Prompt — als JSON. ACHTUNG: API-Keys " +
                            "stehen im KLARTEXT drin, behandele die Datei wie ein Passwort."
                    )
                    InfoParagraph(
                        "Beide Bundles enthalten KEINE Profile (Server-URL, Username, Token) — " +
                            "die bleiben pro Gerät."
                    )
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    // Sub-Sektion: Chat-Backup
                    SubsectionLabel("Konversationen (ZIP)")
                    BackupSection(vm = vm)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Sub-Sektion: Settings-Backup
                    SubsectionLabel("Einstellungen + API-Keys (JSON)")
                    SettingsTransferSection(vm = vm)
                }
            }

            ExpandableSection(
                title = "Über",
                subtitle = "Pocket Claude",
                initiallyExpanded = false,
            ) {
                AboutCard()
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Strukturelle Composables für die neue Settings-Hierarchie.
//  Jede Top-Level-Sektion ist ein einzelner Composable mit klarem Scope.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Profil & Server — kompakte Card, immer sichtbar.
 * URL-Feld + Username-Status + Action-Buttons + Profil-Liste falls Multi-User.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileAndServerCard(
    vm: SettingsViewModel,
    settings: de.smartzone.pocketclaude.data.AppSettings,
    testResult: ConnectionTestResult,
) {
    var changePwOpen by remember { mutableStateOf(false) }
    var reLoginOpen by remember { mutableStateOf(false) }
    val activeUsername = settings.activeProfile?.username.orEmpty()
    val sessionOk = settings.serverToken.isNotBlank()

    SectionHeader(
        text = "Profil & Server",
        infoTitle = "Wie ist mein Handy mit dem Server verbunden?",
    ) {
        InfoParagraph(
            "Jedes Profil ist ein eigener Server-Endpoint mit eigenem Username. " +
                "Du kannst mehrere Profile parallel anlegen (z.B. zuhause + unterwegs) " +
                "und unten in der Liste umschalten."
        )
        InfoBulletParagraph(
            "Server-URL:",
            "Wenn der Server auf Deinem Mac läuft, startet er normalerweise einen " +
                "Cloudflare-Quick-Tunnel mit URL wie https://abc-xyz-123.trycloudflare.com. " +
                "Im Heim-WLAN geht auch http://<Mac-IP>:8787."
        )
        InfoBulletParagraph(
            "Anmeldung:",
            "Erst-Login mit dem Initial-Passwort des Server-Admins. Server zwingt " +
                "Dich dann zu einem eigenen Passwort. Kein API-Key aufs Handy."
        )
    }

    // Profil-Liste (Multi-User), nur wenn relevant
    if (settings.profiles.isNotEmpty() || settings.activeProfile == null) {
        ProfilesCard(vm = vm, settings = settings)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = settings.serverUrl,
                onValueChange = vm::setServerUrl,
                label = { Text("Server-URL") },
                placeholder = { Text("https://pocket-claude.deine-domain.de") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            // Username + Session-Status als Info-Block (read-only, Editier-Pfad
            // läuft über „Neu anmelden").
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "Benutzername",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    activeUsername.ifBlank { "(noch nicht angemeldet)" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (activeUsername.isNotBlank()) FW.Medium else FW.Normal,
                )
                Text(
                    if (sessionOk) "✓ Session aktiv"
                    else "Nicht angemeldet — bitte unten anmelden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sessionOk) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilledTonalButton(
                    onClick = vm::testConnection,
                    enabled = settings.isConfigured && testResult !is ConnectionTestResult.Testing,
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Verbindung testen") }
                FilledTonalButton(
                    onClick = { reLoginOpen = true },
                    enabled = activeUsername.isNotBlank() && settings.serverUrl.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(if (sessionOk) "Neu anmelden" else "Anmelden") }
                FilledTonalButton(
                    onClick = { changePwOpen = true },
                    enabled = sessionOk,
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Passwort ändern") }
                if (sessionOk) {
                    TextButton(onClick = { vm.logoutActiveProfile() }) {
                        Text("Abmelden", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            TestResultIndicator(testResult)
        }
    }

    if (reLoginOpen) {
        ReLoginDialog(vm = vm, onDismiss = { reLoginOpen = false })
    }
    if (changePwOpen) {
        ChangePasswordDialog(vm = vm, forced = false, onDone = { changePwOpen = false })
    }
}

/**
 * Claude-Verhalten — Denktiefe + System-Prompt + Skills in einer
 * gemeinsamen Card. Das ist konzeptionell zusammenhängend (was/wie steuert
 * Claudes Antwort) und vorher unnötig in zwei separate Sektionen aufgeteilt.
 */
@Composable
private fun ClaudeBehaviorCard(
    vm: SettingsViewModel,
    settings: de.smartzone.pocketclaude.data.AppSettings,
) {
    SectionHeader(
        text = "Claude",
        infoTitle = "Denktiefe, Persona, Skills",
    ) {
        InfoBulletParagraph(
            "Denktiefe:",
            "Wie ausführlich Claude vor der Antwort intern reasoned. Höher = bessere " +
                "Qualität bei komplexen Fragen, längere Wartezeit. `xhigh` ist Opus-4.7-only " +
                "und liegt zwischen `high` und `max`."
        )
        InfoBulletParagraph(
            "System-Prompt:",
            "Standard = Anthropic-Default wie auf claude.ai. Freizügig = neutrale Adult-" +
                "to-Adult-Variante mit weniger Hedging. Ultra-Liberal = minimaler Refusal-" +
                "Layer, nur harte Hard-Limits (Minor-Safety, Illegal-Synthesis/Malware). " +
                "Eigener = komplett selbst vorgegeben."
        )
        InfoBulletParagraph(
            "Skills:",
            "Welche Tools darf Claude nutzen — WebSearch, WebFetch, Code-Ausführung. " +
                "Diese Werte sind der Standard für neue Chats. Pro-Chat-Override via ⋮-Menü " +
                "oben rechts im Chat."
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Denktiefe
            SubsectionLabel("Denktiefe")
            EffortChips(
                selected = settings.effort,
                onSelect = vm::setEffort,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // System-Prompt
            SubsectionLabel("System-Prompt")
            SystemPromptModeChips(
                selected = settings.systemPromptMode,
                onSelect = vm::setSystemPromptMode,
            )
            if (settings.systemPromptMode == SystemPromptMode.CUSTOM) {
                OutlinedTextField(
                    value = settings.customSystemPrompt,
                    onValueChange = vm::setCustomSystemPrompt,
                    label = { Text("Eigener System-Prompt") },
                    placeholder = { Text("Hier kompletten System-Prompt einfügen…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 14,
                    shape = RoundedCornerShape(14.dp),
                )
                Text(
                    "Leer = Standard-Prompt greift.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Skills (Default für alle neuen Chats)
            SubsectionLabel("Skills — Standard für neue Chats")
            SkillsDefaultsSection(vm = vm)
        }
    }
}

/** Theme-Auswahl als kompakte Card (3 Filter-Chips). */
@Composable
private fun ThemeCard(
    settings: de.smartzone.pocketclaude.data.AppSettings,
    vm: SettingsViewModel,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SubsectionLabel("Theme")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { vm.setThemeMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Hell"
                                    ThemeMode.DARK -> "Dunkel"
                                }
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Lange Nachrichten einklappen (ChatGPT-Style)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Lange eigene Nachrichten einklappen",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Ab ~6 Zeilen wird Dein Input abgeschnitten mit „Mehr anzeigen\". Spart " +
                            "Scrollarbeit beim Lesen langer Antworten. Tap auf die Bubble klappt sie auf.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.collapseLongUserMessages,
                    onCheckedChange = { vm.setCollapseLongUserMessages(it) },
                )
            }
        }
    }
}

/** Language picker: dropdown with system-default + all bundled locales.
 *  Picking a value writes to LocalePrefs and recreates the Activity so the
 *  new strings.xml takes effect immediately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageCard() {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(LocalePrefs.get(ctx)) }

    val languageEntries = listOf(
        "" to stringResource(de.smartzone.pocketclaude.R.string.language_system_default),
        "en" to stringResource(de.smartzone.pocketclaude.R.string.language_english),
        "de" to stringResource(de.smartzone.pocketclaude.R.string.language_german),
        "es" to stringResource(de.smartzone.pocketclaude.R.string.language_spanish),
        "fr" to stringResource(de.smartzone.pocketclaude.R.string.language_french),
        "pt-BR" to stringResource(de.smartzone.pocketclaude.R.string.language_portuguese_br),
        "zh" to stringResource(de.smartzone.pocketclaude.R.string.language_chinese),
        "ja" to stringResource(de.smartzone.pocketclaude.R.string.language_japanese),
    )
    val currentLabel = languageEntries.firstOrNull { it.first == current }?.second
        ?: languageEntries.first().second

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SubsectionLabel(stringResource(de.smartzone.pocketclaude.R.string.language_label))
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = currentLabel,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    shape = RoundedCornerShape(14.dp),
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    languageEntries.forEach { (tag, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                expanded = false
                                if (tag != current) {
                                    LocalePrefs.set(ctx, tag)
                                    current = tag
                                    (ctx as? Activity)?.recreate()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Über-Sektion — minimal, nur App-Name + Beschreibung. */
@Composable
private fun AboutCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Pocket Claude",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Persönliches Chat-Frontend für Deinen Pocket-Claude-Server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Unified Section-Header. Ersetzt `SectionTitleRow` und `SectionTitle` —
 * vorher gab es drei verschiedene Header-Stile, jetzt nur noch einen
 * konsistenten. `infoTitle` + `infoBody` aktivieren den optionalen ⓘ-Button.
 */
@Composable
private fun SectionHeader(
    text: String,
    infoTitle: String? = null,
    infoBody: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
        )
        if (infoTitle != null && infoBody != null) {
            InfoButton(title = infoTitle, body = infoBody)
        }
    }
}

/** Kleiner Untertitel innerhalb einer Card (für Sub-Gruppen wie „Denktiefe",
 *  „System-Prompt", „Skills"). Spart das Stack-on-Stack-Card-Pattern. */
@Composable
private fun SubsectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffortChips(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "off" to "Aus",
        "low" to "Niedrig",
        "medium" to "Mittel",
        "high" to "Hoch",
        "xhigh" to "Sehr hoch",
        "max" to "Maximum",
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                shape = RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemPromptModeChips(
    selected: SystemPromptMode,
    onSelect: (SystemPromptMode) -> Unit,
) {
    val options = listOf(
        SystemPromptMode.STANDARD to "Standard (Anthropic)",
        SystemPromptMode.PERMISSIVE to "Freizügig",
        SystemPromptMode.ULTRA_LIBERAL to "Ultra-Liberal",
        SystemPromptMode.CUSTOM to "Eigener Prompt",
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                shape = RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

/**
 * Quick-Setup-Banner — ganz oben im Settings-Screen, sichtbar bis der User
 * vollständig eingerichtet ist (Profil angelegt + eingeloggt). Zeigt eine
 * kompakte Checkliste mit grünen Haken bzw. Buttons zu den entsprechenden
 * Sections darunter. Wenn TTS via Edge läuft (= sofort einsatzbereit ohne
 * Setup), ist der TTS-Punkt automatisch grün. Sobald alle drei Punkte grün
 * sind, verschwindet das Banner komplett (siehe `!hasProfile || !isLoggedIn`
 * Check beim Aufruf).
 */
@Composable
private fun QuickSetupBanner(
    hasProfile: Boolean,
    isLoggedIn: Boolean,
    ttsReady: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Einrichtung",
                style = MaterialTheme.typography.titleSmall,
            )
            QuickSetupRow(
                done = hasProfile,
                text = if (hasProfile) "Profil angelegt" else "Profil hinzufügen (unten)",
            )
            QuickSetupRow(
                done = isLoggedIn,
                text = if (isLoggedIn) "Server-Login aktiv"
                    else if (hasProfile) "Mit Server-Konto anmelden (unten)"
                    else "Anmelden — kommt nach Schritt 1",
            )
            QuickSetupRow(
                done = ttsReady,
                text = if (ttsReady) "Vorlesen einsatzbereit"
                    else "Vorlesen — optional, Edge-Default braucht kein Setup",
            )
        }
    }
}

@Composable
private fun QuickSetupRow(done: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (done) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (done) de.smartzone.pocketclaude.ui.theme.PocketTheme.colors.success
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * ExpandableSection — collapsible Wrapper für lange Settings-Bereiche.
 *
 * Header-Row mit Titel + Chevron-Icon, klickbar. Body wird animiert ein-/
 * ausgeblendet via `animateContentSize`. Defaultmäßig collapsed; per
 * `initiallyExpanded=true` für die wichtigsten Sections.
 *
 * Optional `infoTitle` + `infoBody`: dann wird ein InfoButton rechts neben
 * dem Titel platziert (analog zu `SectionTitleRow`).
 * Optional `subtitle`: kleiner Hinweis-Text unter dem Titel (z.B. „Stimme,
 * Geschwindigkeit, Auto-Vorlesen") — gibt dem User einen Inhalts-Vorschau-
 * Hinweis im collapsed-Zustand.
 */
@Composable
private fun ExpandableSection(
    title: String,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    infoTitle: String? = null,
    infoBody: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier.animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header-Row: Titel + (optional) Info-Button + Chevron
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (subtitle != null && !expanded) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (infoTitle != null && infoBody != null) {
                InfoButton(title = infoTitle, body = infoBody)
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Einklappen" else "Aufklappen",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            content()
        }
    }
}

/** Kleines farbiges Badge das den Tier-Hint eines API-Keys anzeigt:
 *  - "free"        → orange „Free" (10 RPD Limit)
 *  - "likely_paid" → grün „Paid?" (hat >15 Erfolge ohne FreeTier-Burn → vermutl. Paid)
 *  - "unknown"     → grau „?" (noch zu wenig Daten) */
@Composable
private fun TierBadge(tierHint: String) {
    data class BadgeStyle(val text: String, val color: androidx.compose.ui.graphics.Color)
    val style = when (tierHint) {
        "free" -> BadgeStyle("Free", androidx.compose.ui.graphics.Color(0xFFE89F2C))
        "likely_paid" -> BadgeStyle("Paid?", androidx.compose.ui.graphics.Color(0xFF4CAF50))
        else -> BadgeStyle("?", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(style.color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            style.text,
            style = MaterialTheme.typography.labelSmall,
            color = style.color,
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsSection(
    vm: SettingsViewModel,
    settings: de.smartzone.pocketclaude.data.AppSettings,
) {
    val ttsStatus by vm.ttsStatus.collectAsState()
    val ttsBusy by vm.ttsBusy.collectAsState()
    val ttsError by vm.ttsError.collectAsState()
    val ttsTest by vm.ttsTest.collectAsState()
    var showJsonDialog by remember { mutableStateOf(false) }
    var voiceMenuOpen by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }

    // Provider-Flags auf TtsSection-Ebene, damit alle drei Cards Zugriff haben.
    val provider = ttsStatus?.provider ?: "edge_tts"
    val isGemini = provider == "gemini_api"
    val isCloud = provider == "cloud_tts"
    val isEdge = provider == "edge_tts"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {

    // ============================================================
    // Card A — Provider & Setup
    // ============================================================
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Text(
                "Provider & Setup",
                style = MaterialTheme.typography.titleMedium,
            )

            // Provider-Auswahl: Edge (free, no-setup) / Gemini API (paid key) / Cloud TTS (service account).
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Provider",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    InfoButton(
                        title = "TTS-Provider — drei Optionen",
                    ) {
                        InfoBulletParagraph(
                            "Edge (Default, kein Setup):",
                            "Microsoft Edge's Vorlesestimmen. Komplett gratis, " +
                                "kein API-Key, kein Service-Account. Klangqualität schwächer als " +
                                "Gemini/Chirp, aber für schnelles Vorlesen völlig okay."
                        )
                        InfoBulletParagraph(
                            "Gemini API:",
                            "API-Key von Google AI Studio. Free Tier: 10 Calls/Tag pro Key — " +
                                "mit 1 Key sehr begrenzt, mit Multi-Key-Pool nutzbar. Paid: " +
                                "$0,50/1M Input + $10/1M Audio-Output. Beste Modell-Inferenz."
                        )
                        InfoBulletParagraph(
                            "Cloud TTS:",
                            "Service-Account-JSON von Google Cloud. 1 Mio Zeichen/Monat gratis " +
                                "(~20h Audio) mit Chirp 3 HD. Beste Voice-Quality-Bandbreite, " +
                                "Setup komplexer (Cloud-Projekt + Service-Account)."
                        )
                    }
                }
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = isEdge,
                        onClick = {
                            if (!isEdge && !ttsBusy) vm.setTtsProvider("edge_tts")
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        enabled = !ttsBusy,
                    ) { Text("Edge") }
                    SegmentedButton(
                        selected = isGemini,
                        onClick = {
                            if (!isGemini && !ttsBusy) vm.setTtsProvider("gemini_api")
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        enabled = !ttsBusy,
                    ) { Text("Gemini API") }
                    SegmentedButton(
                        selected = isCloud,
                        onClick = {
                            if (!isCloud && !ttsBusy) vm.setTtsProvider("cloud_tts")
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        enabled = !ttsBusy,
                    ) { Text("Cloud TTS") }
                }
                // Status-Hint mit Farb-Tönung je nach Provider.
                //
                // Wichtig: der Hint trennt jetzt SAUBER zwischen Pool-Effekt
                // (= Round-Robin über aufeinanderfolgende Antworten) und
                // Chunking-Effekt (= mehrere parallele API-Calls pro Antwort).
                // Vorher stand „Round-Robin + Rate-Limiter" im selben Satz —
                // das war irreführend, weil der Rate-Limiter nur greift wenn
                // Chunking AN ist und parallele Calls rausgehen.
                data class Hint(val text: String, val emphasis: Boolean)
                val keySource = ttsStatus?.geminiApiKeySource ?: "none"
                val keyCount = ttsStatus?.geminiApiKeyCount ?: 0
                val chunkOn = ttsStatus?.chunkingEnabled ?: false
                val hint = when {
                    isEdge -> Hint(
                        "✓ Edge-TTS aktiv — gratis, kein Setup. Klangqualität ist standard " +
                            "(Microsoft-Edge-Stimmen). Für mehr Qualität später auf Gemini API " +
                            "oder Cloud TTS wechseln.",
                        emphasis = false,
                    )
                    isGemini && keyCount >= 2 && chunkOn -> Hint(
                        "✓ $keyCount Keys im Pool, Chunking AN. Pro Antwort wird der Text " +
                            "in mehrere parallele Chunks gesplittet — diese werden per " +
                            "Round-Robin auf alle Keys verteilt. Effektiver Burst: " +
                            "≈${keyCount * 3} parallele TTS-Calls (3 RPM pro Key). Schnellster " +
                            "Stream-Start, aber verbraucht das Tageskontingent rasch.",
                        emphasis = false,
                    )
                    isGemini && keyCount >= 2 && !chunkOn -> Hint(
                        "✓ $keyCount Keys im Pool, Chunking AUS. Pro Antwort wird genau " +
                            "1 Key (Round-Robin reihum) verwendet — also keine Burst-Auslastung. " +
                            "Schonend fürs Tageskontingent. Chunking unten anschalten, " +
                            "wenn Du den schnelleren Stream-Start willst.",
                        emphasis = false,
                    )
                    isGemini && ttsStatus?.geminiApiConfigured == true && keySource == "tts" -> Hint(
                        "✓ Gemini API mit 1 TTS-Key. Free-Tier: 3 RPM / 10 RPD pro Key. " +
                            "Mit Chunking AN (s.u.) brennt eine lange Antwort das Tagesbudget " +
                            "schnell ab. Weitere Keys unten hinzufügen für mehr Throughput.",
                        emphasis = false,
                    )
                    isGemini && ttsStatus?.geminiApiConfigured == true && keySource == "image" -> Hint(
                        "ⓘ Aktuell wird der Bilder-API-Key für TTS mitgenutzt. Trag unten " +
                            "einen separaten Free-Tier-Key (oder mehrere) ein, damit TTS- und " +
                            "Image-Quota nicht konkurrieren.",
                        emphasis = false,
                    )
                    isGemini -> Hint(
                        "⚠ Kein API-Key gesetzt. Trag unten Deinen AI-Studio-Key ein " +
                            "(Free-Tier reicht, siehe ⓘ neben dem Feld).",
                        emphasis = true,
                    )
                    isCloud && ttsStatus?.cloudTtsConfigured == true && chunkOn -> Hint(
                        "✓ Cloud TTS aktiv + Chunking AN. 1 Mio Zeichen/Monat Free " +
                            "(Chirp 3 HD), keine relevante RPM-Bremse — Chunking bringt " +
                            "schnelleren Stream-Start ohne Nachteile.",
                        emphasis = false,
                    )
                    isCloud && ttsStatus?.cloudTtsConfigured == true -> Hint(
                        "✓ Cloud TTS aktiv. 1 Mio Zeichen/Monat Free (Chirp 3 HD). " +
                            "Du könntest Chunking aktivieren (s.u.) — bringt bei Cloud TTS " +
                            "schnelleren Stream-Start ohne Limit-Risiko.",
                        emphasis = false,
                    )
                    else -> Hint(
                        "⚠ Kein Service-Account-JSON geladen. Admin muss eines in der " +
                            "Server-GUI hochladen (Cloud-Projekt mit aktivem Billing-Account).",
                        emphasis = true,
                    )
                }
                Text(
                    hint.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hint.emphasis) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // === Chunking-Toggle ===
                // Bewusst HIER in derselben Card wie der Status-Hint, weil
                // der Hint sich auf den Chunking-Stand bezieht. Bei Edge-TTS
                // ausgeblendet — die edge-tts-Lib chunkt intern via WebSocket,
                // ein zusätzliches App-Chunking bringt nichts.
                if (!isEdge) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val chunkingExplicit = ttsStatus?.chunkingExplicit ?: false
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Chunking (parallele Audio-Synthese)",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            val sub = when {
                                chunkingExplicit && chunkOn ->
                                    "Manuell aktiviert."
                                chunkingExplicit && !chunkOn ->
                                    "Manuell deaktiviert."
                                chunkOn ->
                                    "Auto-Default: AN (für ${if (isCloud) "Cloud TTS" else provider})."
                                else ->
                                    "Auto-Default: AUS (für ${if (isGemini) "Gemini API — schont 10 RPD/Key" else provider})."
                            }
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        InfoButton(title = "Was ist Chunking?") {
                            InfoParagraph(
                                "Lange Antworten werden in Teile aufgeteilt und parallel " +
                                    "an die TTS-API geschickt. Das Audio startet schneller, " +
                                    "weil der erste Chunk schon spielt während die anderen " +
                                    "noch synthetisiert werden."
                            )
                            InfoBulletParagraph(
                                "Bei Cloud TTS:",
                                "Empfohlen AN — kein nennenswertes Rate-Limit, schnellerer " +
                                    "Stream-Start ohne Nachteile."
                            )
                            InfoBulletParagraph(
                                "Bei Gemini API:",
                                "Default AUS. Jeder Chunk = ein API-Call. Bei Free-Tier-Keys " +
                                    "(10 RPD pro Key) verbraucht eine gechunkte Antwort schnell " +
                                    "das Tagesbudget. Mit großem Multi-Key-Pool kannst Du es " +
                                    "manuell aktivieren — dann verteilt der Server die Chunks " +
                                    "per Round-Robin auf alle Keys."
                            )
                        }
                        Switch(
                            checked = chunkOn,
                            onCheckedChange = { v -> vm.setTtsChunking(v) },
                            enabled = !ttsBusy,
                        )
                    }
                    if (chunkingExplicit) {
                        TextButton(
                            onClick = { vm.setTtsChunking(null) },
                            enabled = !ttsBusy,
                        ) {
                            Text("Zurück auf Auto-Default")
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Eigenes API-Key-Feld für TTS — nur wenn Provider = Gemini API.
            // Bewusst getrennt von der Image-Gen-API-Key-Eingabe, weil:
            //   - Image-Gen läuft oft über Paid-Account (4K-Output, Pro-Modelle)
            //   - TTS kann komplett über Free-Tier-Account laufen (Gemini-3.1-
            //     Flash-TTS ist im Free Tier verfügbar, verifiziert Mai 2026)
            if (isGemini) {
                // Hinweis-Banner: Image- und TTS-Key können derselbe sein, müssen
                // aber nicht — der Server fällt bei fehlendem TTS-Key automatisch
                // auf den Image-Key zurück. Klärt das verbreitete „warum zwei
                // Felder?"-Verständnis-Problem.
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                ) {
                    Text(
                        "💡 Tipp: Der TTS-Key kann derselbe AI-Studio-Key wie der für Bilder " +
                            "sein. Wenn Du hier keinen einträgst, nutzt der Server automatisch " +
                            "den Bilder-Key (siehe Sektion „Bilder\" unten).",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                TtsGeminiApiKeyField(vm = vm, ttsStatus = ttsStatus, ttsBusy = ttsBusy)
            }

            // Status — nur informativ wenn nicht Edge (Edge braucht nichts).
            if (!isEdge) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val configured = ttsStatus?.configured == true
                    Icon(
                        imageVector = if (configured) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        contentDescription = null,
                        tint = if (configured) de.smartzone.pocketclaude.ui.theme.PocketTheme.colors.success
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (configured) "Aktueller Provider bereit"
                            else "Aktueller Provider nicht einsatzbereit",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (configured && isCloud) {
                            val email = ttsStatus?.clientEmail.orEmpty()
                            if (email.isNotBlank()) {
                                Text(
                                    email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // Lösch-Button für Cloud-TTS-Credentials nur bei Cloud-Provider.
                    if (ttsStatus?.cloudTtsConfigured == true && isCloud) {
                        IconButton(onClick = { vm.deleteTtsCredentials() }, enabled = !ttsBusy) {
                            Icon(Icons.Filled.Delete, contentDescription = "Cloud-TTS-Credentials entfernen")
                        }
                    }
                }
            }

            // JSON-Upload-Button — nur für Cloud-TTS-Pfad (Edge braucht's nicht, Gemini hat eigenen Key-Slot).
            if (isCloud) {
                FilledTonalButton(
                    onClick = {
                        jsonText = ""
                        showJsonDialog = true
                    },
                    enabled = !ttsBusy && settings.isConfigured,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (ttsBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(Icons.Filled.Upload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (ttsStatus?.cloudTtsConfigured == true) "Cloud-TTS-Credentials ersetzen" else "Cloud-TTS Service-Account-JSON einfügen")
                }
            }

            if (!settings.isConfigured) {
                Text(
                    "Bitte erst Server-URL und Token speichern, dann kannst Du das TTS-JSON hochladen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ttsError?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = vm::clearTtsError) { Text("OK") }
            }

            // Cloud-Billing-Widget: Spend, Budget, AI-Pro-Credit-Restwert.
            // Nur sichtbar wenn Cloud-TTS konfiguriert ist (vorher gibt's
            // keinen Service-Account → API-Call ginge ins Leere).
            if (ttsStatus?.cloudTtsConfigured == true) {
                CloudBillingWidget(vm = vm)
            }
        }
    }

    // ============================================================
    // Card B — Stimme & Wiedergabe
    // ============================================================
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Text(
                "Stimme & Wiedergabe",
                style = MaterialTheme.typography.titleMedium,
            )

            // Voice-Auswahl — nach Engine-Tier gruppiert, damit Gemini-Voices
            // klar von den klassischen Cloud-TTS-Voices getrennt sind.
            // Filtert auf den aktuell aktiven Provider: bei `gemini_api` sind
            // nur die `gemini-*`-Voices kompatibel (Direct-API kennt keine
            // Chirp/Studio/Neural2/etc.), bei `cloud_tts` ist alles möglich.
            val allVoices = ttsStatus?.voices.orEmpty()
            val voices = allVoices.filter { provider in it.compatible_providers }
            if (voices.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = voiceMenuOpen,
                    onExpandedChange = { voiceMenuOpen = !voiceMenuOpen },
                ) {
                    // Aktuelle Voice: Label aus der vollen (un-gefilterten) Liste
                    // lesen, damit der Anzeigename sichtbar bleibt — selbst wenn
                    // die Voice mit dem aktuellen Provider nicht kompatibel ist
                    // (Server fällt dann intern auf einen Default zurück).
                    val currentVoice = allVoices.firstOrNull { it.id == settings.ttsVoice }
                    val currentVoiceIncompatible = currentVoice != null &&
                        provider !in currentVoice.compatible_providers
                    OutlinedTextField(
                        value = if (currentVoiceIncompatible)
                            "⚠ ${currentVoice.label} (nicht mit $provider kompatibel)"
                        else currentVoice?.label ?: settings.ttsVoice,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Stimme") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceMenuOpen)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    DropdownMenu(
                        expanded = voiceMenuOpen,
                        onDismissRequest = { voiceMenuOpen = false },
                    ) {
                        val tierOrder = listOf("edge", "gemini", "chirp3hd", "studio", "neural2", "wavenet", "standard")
                        val tierLabels = mapOf(
                            "edge" to "Edge (gratis, kein Setup nötig)",
                            "gemini" to "Gemini (KI-Stimmen — Modell unten wählbar)",
                            "chirp3hd" to "Chirp 3 HD (1 Mio Zeichen/Monat gratis, dann 30 \$/M)",
                            "studio" to "Studio (Premium — 1 Mio Zeichen/Monat gratis, dann 160 \$/M)",
                            "neural2" to "Neural2 (1 Mio gratis, dann 16 \$/M)",
                            "wavenet" to "Wavenet (4 Mio gratis, dann 4 \$/M)",
                            "standard" to "Standard (4 Mio gratis, dann 4 \$/M)",
                        )
                        val grouped = voices.groupBy { it.tier }
                        val orderedTiers = tierOrder.filter { grouped.containsKey(it) } +
                            grouped.keys.filter { it !in tierOrder }
                        orderedTiers.forEachIndexed { idx, tier ->
                            if (idx > 0) HorizontalDivider()
                            Text(
                                text = tierLabels[tier] ?: tier.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp,
                                ),
                            )
                            grouped[tier].orEmpty().forEach { v ->
                                DropdownMenuItem(
                                    text = { Text(v.label) },
                                    onClick = {
                                        vm.setVoice(v.id)
                                        voiceMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Wiedergabe-Geschwindigkeit — diskrete 0.25-Schritte von 0.25x bis 2.0x.
            // Wird als ?rate=... Query-Param an den Server geschickt.
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Geschwindigkeit",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${"%.2f".format(settings.ttsSpeed)}x",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = settings.ttsSpeed,
                    onValueChange = { v ->
                        // auf 0.25-Schritte snappen
                        val snapped = (v * 4f).toInt().coerceIn(1, 8) / 4f
                        if (snapped != settings.ttsSpeed) vm.setSpeed(snapped)
                    },
                    valueRange = 0.25f..2.0f,
                    steps = 6,  // 0.25, 0.50, 0.75, 1.00, 1.25, 1.50, 1.75, 2.00 → 6 Stops zwischen 8 Werten
                    enabled = ttsStatus?.configured == true,
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("0,25x", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("1,00x", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("2,00x", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Auto-Speak Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Antworten automatisch vorlesen", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Sobald Claude antwortet, startet die Wiedergabe — bequem im Auto / unterwegs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.ttsAutoSpeak,
                    onCheckedChange = vm::setAutoSpeak,
                    enabled = ttsStatus?.configured == true,
                )
            }

            // Test-Button
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = vm::testVoice,
                    enabled = ttsStatus?.configured == true && ttsTest !is TtsTestResult.Testing,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Test-Vorlesung")
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = vm::stopAudio,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stopp")
                }
            }
            when (val t = ttsTest) {
                is TtsTestResult.Failure -> Text(
                    t.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
        }
    }

    // ============================================================
    // Card C — TTS-Modell-Wahl (nur sichtbar bei Gemini-Voice)
    // ============================================================
    // Chunking wurde 2026-05-19 nach Card A verschoben (gehört konzeptionell
    // zum Provider-Status, weil sich der Hint-Text dort auf den Chunking-
    // Stand bezieht). Diese Card hier zeigt nur noch die Modell-Wahl, und
    // nur wenn überhaupt eine Gemini-Voice aktiv ist — sonst ist die ganze
    // Card unsichtbar.
    val allVoicesForModel = ttsStatus?.voices.orEmpty()
    val voicesForModel = allVoicesForModel.filter { provider in it.compatible_providers }
    val isGeminiVoice = settings.ttsVoice.startsWith("gemini-") ||
        voicesForModel.firstOrNull { it.id == settings.ttsVoice }?.tier == "gemini"
    val availableModels = ttsStatus?.availableModels.orEmpty()
    if (isGeminiVoice && availableModels.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Gemini-TTS-Modell",
                    style = MaterialTheme.typography.titleMedium,
                )
                run {
                val currentModelId = ttsStatus?.ttsModel
                    ?: availableModels.firstOrNull { it.default }?.id
                    ?: availableModels.first().id
                val currentModel = availableModels.firstOrNull { it.id == currentModelId }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "TTS-Modell",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    InfoButton(title = "TTS-Modell wählen") {
                        InfoParagraph(
                            "Das Modell bestimmt die Generation der Gemini-TTS-Engine. " +
                                "Die Stimme klingt je nach Modell unterschiedlich — Pacing, " +
                                "Pitch-Stabilität und Emotional-Range variieren."
                        )
                        InfoBulletParagraph(
                            "Gemini 2.5 Flash TTS:",
                            "Aktueller Default. Sehr gute Qualität, am günstigsten."
                        )
                        InfoBulletParagraph(
                            "Gemini 3.1 Flash TTS:",
                            "Neuere Generation, lebendiger. Free-Tier-Limit: 10 Calls/Tag/Key."
                        )
                        InfoBulletParagraph(
                            "Gemini 2.5 Pro TTS:",
                            "Pro-Modell, langsamer + teurer. Nur wählen wenn der Mehrpreis " +
                                "tatsächlich hörbar besser klingt."
                        )
                    }
                }
                var modelMenuOpen by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = modelMenuOpen,
                    onExpandedChange = { if (!ttsBusy) modelMenuOpen = !modelMenuOpen },
                ) {
                    val priceText = currentModel?.priceHint?.takeIf { it.isNotBlank() }.orEmpty()
                    OutlinedTextField(
                        value = currentModel?.label ?: currentModelId,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Modell") },
                        supportingText = {
                            if (priceText.isNotEmpty()) Text(priceText)
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuOpen)
                        },
                        enabled = !ttsBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    DropdownMenu(
                        expanded = modelMenuOpen,
                        onDismissRequest = { modelMenuOpen = false },
                    ) {
                        availableModels.forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(m.label)
                                        if (m.priceHint.isNotBlank()) {
                                            Text(
                                                m.priceHint,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    if (m.id != currentModelId) vm.setTtsModel(m.id)
                                    modelMenuOpen = false
                                },
                            )
                        }
                    }
                }
                }  // close run {}
            }  // close Column
        }  // close Card
    }  // close if (isGeminiVoice && ...)

    } // outer Column (Cards A/B/C)

    if (showJsonDialog) {
        AlertDialog(
            onDismissRequest = { showJsonDialog = false },
            title = { Text("Service-Account-JSON einfügen") },
            text = {
                Column {
                    Text(
                        "Inhalt der Google-Cloud-Service-Account-JSON-Datei hier einfügen. " +
                                "Erstellbar unter console.cloud.google.com → IAM → Service-Konten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = { jsonText = it },
                        placeholder = { Text("{ \"type\": \"service_account\", ... }") },
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (jsonText.isNotBlank()) {
                            vm.uploadTtsCredentials(jsonText)
                            showJsonDialog = false
                        }
                    },
                ) { Text("Hochladen") }
            },
            dismissButton = {
                TextButton(onClick = { showJsonDialog = false }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun TestResultIndicator(state: ConnectionTestResult) {
    when (state) {
        ConnectionTestResult.Idle -> {}
        ConnectionTestResult.Testing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("teste…", style = MaterialTheme.typography.bodySmall)
        }
        is ConnectionTestResult.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = PocketTheme.colors.success,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "OK · ${state.model}",
                style = MaterialTheme.typography.bodySmall,
                color = PocketTheme.colors.success,
            )
        }
        is ConnectionTestResult.Failure -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                state.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun BackupSection(vm: SettingsViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by vm.backup.collectAsState()

    // File-Picker für Import — akzeptiert ZIP + andere Container-Formate
    val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    vm.stageImport(bytes)
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    context,
                    "Datei konnte nicht gelesen werden: ${e.message}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // Bei Exported: ZIP in Cache schreiben + Share-Intent öffnen
    androidx.compose.runtime.LaunchedEffect(state) {
        val s = state
        if (s is SettingsViewModel.BackupState.Exported) {
            shareBackupZip(context, s.bytes, s.filename)
            vm.resetBackupState()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Alle Chats vom Server als ZIP exportieren (für Google Drive, Telegram …) " +
                    "oder ein bestehendes Backup hier importieren.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val busy = state is SettingsViewModel.BackupState.Exporting ||
                state is SettingsViewModel.BackupState.Verifying ||
                state is SettingsViewModel.BackupState.Importing
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { vm.startExport() },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.Upload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Exportieren")
                }
                FilledTonalButton(
                    onClick = {
                        // ZIP-MIME-Types — Android lässt unterschiedliche
                        // MIME-Strings je nach Quelle (z.B. Telegram nutzt
                        // application/zip, andere application/x-zip-compressed).
                        importPicker.launch(arrayOf(
                            "application/zip",
                            "application/x-zip-compressed",
                            "application/octet-stream",
                            "*/*",
                        ))
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Importieren")
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (state) {
                            is SettingsViewModel.BackupState.Importing -> "Importiere…"
                            is SettingsViewModel.BackupState.Verifying -> "Prüfe Backup…"
                            else -> "Lade vom Server…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    // Confirm-Dialog: Manifest-Info anzeigen + Replace/Merge wählen
    val ready = state as? SettingsViewModel.BackupState.ReadyToImport
    if (ready != null) {
        AlertDialog(
            onDismissRequest = { vm.resetBackupState() },
            title = { Text("Backup importieren") },
            text = {
                Column {
                    val m = ready.manifest
                    Text(
                        "Erstellt: ${m.createdAt.substringBefore('T')}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${m.conversationCount} Chats · ${m.messageCount} Nachrichten · " +
                            "${m.attachmentCount} Anhänge",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Server-Version ${m.serverVersion} · Schema v${m.schemaVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Replace = aktueller Server-Stand wird komplett überschrieben " +
                            "(vorher wird automatisch ein internes Backup gespeichert).\n\n" +
                            "Merge = nur Chats, die hier noch nicht existieren, werden hinzugefügt.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.confirmImport("merge") }) {
                        Text("Merge")
                    }
                    TextButton(onClick = { vm.confirmImport("replace") }) {
                        Text("Replace", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.resetBackupState() }) { Text("Abbrechen") }
            },
        )
    }

    val success = state as? SettingsViewModel.BackupState.ImportSuccess
    if (success != null) {
        AlertDialog(
            onDismissRequest = { vm.resetBackupState() },
            title = { Text("Import erfolgreich") },
            text = {
                Column {
                    val r = success.response
                    Text(
                        if (r.mode == "replace")
                            "${r.conversationsAdded} Chats / ${r.messagesImported} Nachrichten" +
                                " geladen.\nServer-Stand komplett ersetzt."
                        else
                            "${r.conversationsAdded} neue Chats hinzugefügt, " +
                                "${r.conversationsSkipped} bereits vorhanden (übersprungen).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (r.restartRecommended) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Bitte den Server kurz neu starten (im Server-Manager auf " +
                                "dem Mini-PC), damit alle DB-Connections die neue Datei nutzen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.resetBackupState() }) { Text("OK") }
            },
        )
    }

    val failure = state as? SettingsViewModel.BackupState.Failure
    if (failure != null) {
        AlertDialog(
            onDismissRequest = { vm.resetBackupState() },
            title = { Text("Fehler") },
            text = { Text(failure.reason, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { vm.resetBackupState() }) { Text("OK") }
            },
        )
    }

    // Password-Prompt beim Export — User kann PW eingeben oder leer lassen
    if (state is SettingsViewModel.BackupState.AwaitingExportPassword) {
        var pw by remember { mutableStateOf("") }
        var showPw by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { vm.resetBackupState() },
            title = { Text("Backup verschlüsseln?") },
            text = {
                Column {
                    Text(
                        "Optional: Passwort für AES-256-Verschlüsselung. Wenn Du das Backup auf " +
                            "Google Drive oder per Messenger weitergibst, sind alle Chats damit " +
                            "geschützt. Leer lassen für ein unverschlüsseltes ZIP.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pw,
                        onValueChange = { pw = it },
                        label = { Text("Passwort (optional)") },
                        singleLine = true,
                        visualTransformation = if (showPw) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPw = !showPw }) {
                                Icon(
                                    if (showPw) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = if (showPw) "Passwort verbergen"
                                                         else "Passwort anzeigen",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.runExport(pw) }) {
                    Text(if (pw.isNotBlank()) "Verschlüsselt exportieren" else "Exportieren")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.resetBackupState() }) { Text("Abbrechen") }
            },
        )
    }

    // Password-Prompt beim Import (Backup ist verschlüsselt)
    val pwPrompt = state as? SettingsViewModel.BackupState.AwaitingImportPassword
    if (pwPrompt != null) {
        var pw by remember { mutableStateOf("") }
        var showPw by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { vm.resetBackupState() },
            title = { Text("Backup ist verschlüsselt") },
            text = {
                Column {
                    Text(
                        if (pwPrompt.previousAttemptFailed)
                            "Passwort war falsch. Bitte erneut eingeben."
                        else
                            "Dieses Backup wurde mit einem Passwort geschützt. Bitte eingeben.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (pwPrompt.previousAttemptFailed) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pw,
                        onValueChange = { pw = it },
                        label = { Text("Passwort") },
                        singleLine = true,
                        visualTransformation = if (showPw) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPw = !showPw }) {
                                Icon(
                                    if (showPw) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = null,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.retryImportWithPassword(pw) },
                    enabled = pw.isNotBlank(),
                ) { Text("Entsperren") }
            },
            dismissButton = {
                TextButton(onClick = { vm.resetBackupState() }) { Text("Abbrechen") }
            },
        )
    }
}

/** Schreibt die Backup-Bytes in cacheDir/exports/, holt sich eine
 *  FileProvider-Uri und öffnet den Share-Chooser. */
private fun shareBackupZip(context: android.content.Context, bytes: ByteArray, filename: String) {
    try {
        val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
        val file = java.io.File(dir, filename)
        file.writeBytes(bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Pocket Claude Backup")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Backup teilen"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context, "Teilen fehlgeschlagen: ${e.message}", android.widget.Toast.LENGTH_LONG,
        ).show()
    }
}

@Composable
private fun ProfilesCard(vm: SettingsViewModel, settings: de.smartzone.pocketclaude.data.AppSettings) {
    var addDialogOpen by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameLabel by remember { mutableStateOf("") }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (settings.profiles.isEmpty()) {
                Text(
                    "Noch kein Profil — leg unten URL + Token an, dann wird automatisch ein „Standard\"-Profil erstellt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                for (p in settings.profiles) {
                    val isActive = p.id == settings.activeProfileId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .clickable { vm.activateProfile(p.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (isActive) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isActive) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                p.label.ifBlank { "Profil" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isActive) FW.SemiBold else FW.Normal,
                            )
                            Text(
                                p.serverUrl.ifBlank { "(keine URL)" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        IconButton(onClick = { renameId = p.id; renameLabel = p.label }) {
                            Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "Umbenennen",
                                modifier = Modifier.size(18.dp))
                        }
                        if (settings.profiles.size > 1) {
                            IconButton(onClick = { confirmDeleteId = p.id }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Profil löschen",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = { addDialogOpen = true },
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Profil hinzufügen")
            }
        }
    }

    if (addDialogOpen) {
        AddProfileLoginDialog(
            vm = vm,
            onDismiss = { addDialogOpen = false },
        )
    }

    if (renameId != null) {
        AlertDialog(
            onDismissRequest = { renameId = null },
            title = { Text("Profil umbenennen") },
            text = {
                OutlinedTextField(
                    value = renameLabel,
                    onValueChange = { renameLabel = it },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameLabel.isNotBlank()) vm.renameProfile(renameId!!, renameLabel)
                    renameId = null
                }) { Text("Speichern") }
            },
            dismissButton = {
                TextButton(onClick = { renameId = null }) { Text("Abbrechen") }
            },
        )
    }

    if (confirmDeleteId != null) {
        val p = settings.profiles.firstOrNull { it.id == confirmDeleteId }
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Profil löschen?") },
            text = {
                Text("Profil „${p?.label}\" wird lokal entfernt. Die Daten auf dem Server bleiben unangetastet — Du kannst Dich mit demselben Benutzernamen jederzeit wieder anmelden.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteId?.let { vm.deleteProfile(it) }
                    confirmDeleteId = null
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text("Abbrechen") }
            },
        )
    }
}


// =====================================================================
// Login + Forced-Password-Change Dialoge
// =====================================================================

@Composable
private fun AddProfileLoginDialog(vm: SettingsViewModel, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    val loginState by vm.loginState.collectAsState()
    val working = loginState is LoginUiState.Working

    // Beim Öffnen: alten State (z.B. ein hängendes Success aus einem
    // früheren Login) wegputzen, damit das LaunchedEffect unten nicht
    // sofort feuert und den Dialog wieder zumacht.
    var loginAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.clearLoginState() }

    // Nur auf Success reagieren, NACHDEM der User auf "Anmelden" geklickt hat.
    LaunchedEffect(loginState) {
        if (loginAttempted && loginState is LoginUiState.Success) {
            onDismiss()
            vm.clearLoginState()
        }
    }
    DisposableEffect(Unit) {
        onDispose { vm.clearLoginState() }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Profil hinzufügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Anzeige-Name (z.B. „Marie“)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    enabled = !working,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server-URL") },
                    placeholder = { Text("https://…trycloudflare.com") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    enabled = !working,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Benutzername") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    enabled = !working,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Passwort") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPw = !showPw }) {
                            Icon(
                                if (showPw) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPw) "Verbergen" else "Anzeigen",
                            )
                        }
                    },
                    enabled = !working,
                )
                val state = loginState
                if (state is LoginUiState.Failure) {
                    Text(
                        state.reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (working) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Anmeldung …", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working,
                onClick = {
                    if (url.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                        loginAttempted = true
                        vm.addProfileAndLogin(label, url, username, password)
                    }
                },
            ) { Text("Anmelden") }
        },
        dismissButton = {
            TextButton(onClick = { if (!working) onDismiss() }) { Text("Abbrechen") }
        },
    )
}

@Composable
private fun ReLoginDialog(vm: SettingsViewModel, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val active = settings.activeProfile
    var password by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    val loginState by vm.loginState.collectAsState()
    val working = loginState is LoginUiState.Working
    var loginAttempted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.clearLoginState() }
    LaunchedEffect(loginState) {
        if (loginAttempted && loginState is LoginUiState.Success) {
            onDismiss()
            vm.clearLoginState()
        }
    }
    DisposableEffect(Unit) { onDispose { vm.clearLoginState() } }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Erneut anmelden") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Profil „${active?.label ?: ""}“ als „${active?.username ?: "?"}“",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Passwort") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPw = !showPw }) {
                            Icon(
                                if (showPw) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    enabled = !working,
                )
                val state = loginState
                if (state is LoginUiState.Failure) {
                    Text(state.reason, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Anmeldung …", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && password.isNotBlank(),
                onClick = {
                    loginAttempted = true
                    vm.relogActiveProfile(password)
                },
            ) { Text("Anmelden") }
        },
        dismissButton = {
            TextButton(onClick = { if (!working) onDismiss() }) { Text("Abbrechen") }
        },
    )
}

/**
 * Wird vom SettingsScreen am obersten Level eingehängt. Beobachtet den
 * Login-State und zeigt nach Login mit `must_change_password=true` den
 * Forced-PW-Change-Dialog. Außerdem über `triggerManually` für den
 * normalen "Passwort ändern"-Button.
 */
@Composable
fun ForcedPasswordChangeWatcher(vm: SettingsViewModel) {
    var forcedOpen by remember { mutableStateOf(false) }
    val loginState by vm.loginState.collectAsState()
    LaunchedEffect(loginState) {
        val s = loginState
        if (s is LoginUiState.Success && s.mustChangePassword) {
            forcedOpen = true
        }
    }
    if (forcedOpen) {
        ChangePasswordDialog(
            vm = vm,
            forced = true,
            onDone = { forcedOpen = false; vm.clearLoginState() },
        )
    }
}

@Composable
fun ChangePasswordDialog(vm: SettingsViewModel, forced: Boolean, onDone: () -> Unit) {
    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var newPw2 by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!forced && !working) onDone() },
        title = { Text(if (forced) "Passwort jetzt setzen" else "Passwort ändern") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (forced) {
                    Text(
                        "Beim ersten Login musst Du ein neues Passwort vergeben.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    OutlinedTextField(
                        value = oldPw,
                        onValueChange = { oldPw = it },
                        label = { Text("Aktuelles Passwort") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !working,
                    )
                }
                OutlinedTextField(
                    value = newPw,
                    onValueChange = { newPw = it },
                    label = { Text("Neues Passwort (min. 8 Zeichen)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPw = !showPw }) {
                            Icon(
                                if (showPw) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    enabled = !working,
                )
                OutlinedTextField(
                    value = newPw2,
                    onValueChange = { newPw2 = it },
                    label = { Text("Neues Passwort wiederholen") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !working,
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Speichere …", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working,
                onClick = {
                    if (newPw.length < 8) { error = "Min. 8 Zeichen."; return@TextButton }
                    if (newPw != newPw2) { error = "Passwörter stimmen nicht überein."; return@TextButton }
                    if (!forced && oldPw.isBlank()) { error = "Altes Passwort eingeben."; return@TextButton }
                    error = null
                    working = true
                    vm.changePassword(
                        oldPassword = if (forced) null else oldPw,
                        newPassword = newPw,
                    ) { result ->
                        working = false
                        result.onSuccess { onDone() }
                            .onFailure { e -> error = e.message ?: e::class.java.simpleName }
                    }
                },
            ) { Text("Speichern") }
        },
        dismissButton = {
            if (!forced) TextButton(onClick = { if (!working) onDone() }) { Text("Abbrechen") }
        },
    )
}


// =====================================================================
// Settings Export / Import (vollständiges Bundle inkl. API-Keys)
// =====================================================================
@Composable
private fun SettingsTransferSection(vm: SettingsViewModel) {
    val state by vm.settingsTransfer.collectAsState()
    val context = LocalContext.current

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    vm.importAllSettings(bytes)
                } else {
                    // Picker abgebrochen oder Datei leer
                }
            } catch (e: Exception) {
                // Best-effort — error wandert via VM-State weiter
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Alle Settings + API-Keys als Datei. Profile bleiben raus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { vm.exportAllSettings() },
                    enabled = state !is SettingsViewModel.SettingsTransferState.Exporting &&
                              state !is SettingsViewModel.SettingsTransferState.Importing,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Upload, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Exportieren")
                }
                FilledTonalButton(
                    onClick = {
                        // OpenDocument expects MIME-Type-Array; JSON ist nicht überall
                        // sauber registriert → application/* zulassen.
                        pickerLauncher.launch(arrayOf("application/json", "application/*", "*/*"))
                    },
                    enabled = state !is SettingsViewModel.SettingsTransferState.Exporting &&
                              state !is SettingsViewModel.SettingsTransferState.Importing,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Importieren")
                }
            }

            // State-Anzeige
            when (val s = state) {
                SettingsViewModel.SettingsTransferState.Idle -> Unit
                SettingsViewModel.SettingsTransferState.Exporting,
                SettingsViewModel.SettingsTransferState.Importing -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (s == SettingsViewModel.SettingsTransferState.Exporting)
                            "Exportiere…" else "Importiere…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is SettingsViewModel.SettingsTransferState.Exported -> {
                    // Datei sofort sharen — User wählt Ziel (E-Mail, Files, Drive…)
                    LaunchedEffect(s) {
                        shareSettingsJson(context, s.bytes, s.filename)
                    }
                    Text(
                        "✓ Exportiert: ${s.filename} (${s.bytes.size} Bytes). Teilen-Dialog geöffnet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = vm::resetSettingsTransfer) { Text("OK") }
                }
                is SettingsViewModel.SettingsTransferState.ImportSuccess -> {
                    Text(
                        "✓ Import erfolgreich: ${s.appliedServer} Server-Settings übernommen, " +
                            "${s.ttsKeysImported} API-Keys importiert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = vm::resetSettingsTransfer) { Text("OK") }
                }
                is SettingsViewModel.SettingsTransferState.Failure -> {
                    Text(
                        s.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = vm::resetSettingsTransfer) { Text("OK") }
                }
            }
        }
    }
}

/** Schreibt das Settings-Bundle in die App-Cache + öffnet den Share-Sheet. */
private fun shareSettingsJson(
    context: android.content.Context,
    bytes: ByteArray,
    filename: String,
) {
    val dir = java.io.File(context.cacheDir, "settings-exports").apply { mkdirs() }
    val file = java.io.File(dir, filename)
    file.writeBytes(bytes)
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(send, "Settings-Export teilen")
    )
}


// =====================================================================
// Skills-Defaults (User-Level — gilt für alle Chats ohne Override)
// =====================================================================
@Composable
private fun SkillsDefaultsSection(vm: SettingsViewModel) {
    val skills by vm.defaultSkills.collectAsState()
    val busy by vm.skillsBusy.collectAsState()
    val current = skills

    // Diese Sektion wird nur noch innerhalb der ClaudeBehaviorCard aufgerufen
    // und braucht keine eigene Card mehr drumherum — sonst entsteht Card-in-
    // Card und der visuelle Stack wird doppelt. Spacing reicht.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (current == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Lade Skills-Einstellungen…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SkillToggleRow(
                label = "WebSearch",
                description = "Aktuelle Infos aus dem Web suchen.",
                enabled = !busy,
                checked = current.webSearch,
                onCheckedChange = {
                    vm.setDefaultSkills(current.copy(webSearch = it))
                },
            )
            SkillToggleRow(
                label = "WebFetch",
                description = "Konkrete URLs abrufen + zusammenfassen.",
                enabled = !busy,
                checked = current.webFetch,
                onCheckedChange = {
                    vm.setDefaultSkills(current.copy(webFetch = it))
                },
            )
            SkillToggleRow(
                label = "Code-Ausführung",
                description = "Bash/Python in Server-Sandbox für Berechnungen.",
                enabled = !busy,
                checked = current.codeExecution,
                onCheckedChange = {
                    vm.setDefaultSkills(current.copy(codeExecution = it))
                },
            )
        }
    }
}

/**
 * Zeile mit Label + Beschreibung links und Switch rechts. Wird sowohl in den
 * Settings-Defaults als auch im Per-Chat-Override-Dialog verwendet — deshalb
 * `internal` und nicht private.
 */
@Composable
internal fun SkillToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}


// =====================================================================
// TTS Gemini-API-Key-Pool (Multi-Key + Rate-Limiter im Server)
// =====================================================================
@Composable
private fun TtsGeminiApiKeyField(
    vm: SettingsViewModel,
    ttsStatus: de.smartzone.pocketclaude.data.TtsStatusDto?,
    ttsBusy: Boolean,
) {
    val pool by vm.ttsKeyPool.collectAsState()
    var input by remember { mutableStateOf("") }
    var labelInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Gemini-API-Keys (Pool für TTS)",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            InfoButton(title = "Multi-Key-Pool & Free-Tier-Limits") {
                InfoParagraph(
                    "Hier kannst Du mehrere AI-Studio-API-Keys hinterlegen. Der Server " +
                        "verteilt TTS-Calls per Round-Robin + Rate-Limiter über alle Keys — " +
                        "so umgehst Du die Per-Projekt-Limits des Free Tier."
                )
                InfoBulletParagraph(
                    "Warum mehrere Keys?",
                    "Live-Test Mai 2026: Gemini 3.1 Flash TTS Preview hat im Free Tier nur " +
                        "10 Requests pro TAG pro Projekt. Gemini 2.5 Flash TTS Preview ist " +
                        "großzügiger (3 RPM rolling). Für eine Chat-App mit Chunking ist das " +
                        "knapp. Mit 3-5 Keys (aus separaten Projekten) skalierst Du proportional."
                )
                InfoBulletParagraph(
                    "Wie kommst Du an mehrere Keys?",
                    "Auf aistudio.google.com pro Key ein NEUES Projekt erstellen (im selben " +
                        "Google-Konto möglich — Tier hängt am Projekt, nicht am Account). " +
                        "Alternativ: separater Google-Account. Beides funktioniert, beides ist " +
                        "Free Tier, keine Kreditkarte nötig."
                )
                InfoBulletParagraph(
                    "Dispatcher:",
                    "Server merkt sich pro Key die letzten Aufrufe (sliding window), 2 RPM pro " +
                        "Key als sichere Grenze unter dem 3-RPM-Limit. Bei einem Chunk-Aufruf " +
                        "pickt er den Key mit den meisten freien Slots."
                )
                InfoBulletParagraph(
                    "Alternative:",
                    "Wenn Du nicht mehrere Keys verwalten willst: nimm Cloud TTS (Service-" +
                        "Account, 1M Zeichen/Monat Free-Contingent, keine RPM-Limits) ODER " +
                        "Paid-Tier-Gemini-Key (1 Key reicht, gedeckt von Deinem $10 AI-Pro-" +
                        "Credit + 8 €-Hard-Cap)."
                )
            }
        }

        // Aktuelle Pool-Übersicht
        if (pool.isEmpty()) {
            Text(
                "Noch keine Keys im Pool. Trage unten den ersten ein.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "${pool.size} Key${if (pool.size == 1) "" else "s"} im Pool — " +
                    "geschätzt ${pool.size * 2} Calls/Min Throughput.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            pool.forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.masked, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                            TierBadge(entry.tierHint)
                        }
                        if (entry.label.isNotBlank()) {
                            Text(
                                entry.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (entry.successCount > 0) {
                            Text(
                                "${entry.successCount} TTS-Calls erfolgreich",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(
                        onClick = { vm.removeTtsKey(entry.id) },
                        enabled = !ttsBusy,
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Key entfernen")
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Neuer-Key-Eingabe
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Neuen Key hinzufügen (AIza…)") },
            placeholder = { Text("AIza…") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        imageVector = if (showKey) Icons.Filled.VisibilityOff
                                      else Icons.Filled.Visibility,
                        contentDescription = if (showKey) "Verbergen" else "Anzeigen",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        OutlinedTextField(
            value = labelInput,
            onValueChange = { labelInput = it },
            label = { Text("Label (optional, z.B. \"Account 2\")") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        FilledTonalButton(
            onClick = {
                if (input.isNotBlank()) {
                    vm.addTtsKey(input.trim(), labelInput.trim())
                    input = ""
                    labelInput = ""
                }
            },
            enabled = !ttsBusy && input.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (ttsBusy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Key zum Pool hinzufügen")
        }
    }
}


// =====================================================================
// Image-Generation Settings (API-Key + Status)
// =====================================================================
@Composable
private fun ImageGenSection(vm: SettingsViewModel) {
    val cfg by vm.imageConfig.collectAsState()
    val busy by vm.imageKeyBusy.collectAsState()
    val msg by vm.imageKeyMessage.collectAsState()
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Dein eigener Google-AI-Studio-API-Key. Wird nur für Dich gespeichert.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Status
            cfg?.let { c ->
                if (c.configured) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text("Key hinterlegt: ${c.apiKeyMasked ?: ""}",
                             style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("Kein API-Key — Bild-Modus ist deaktiviert.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error)
                }
            } ?: Text("Lade Status…", style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Neuer API-Key") },
                placeholder = { Text("AIzaSy…") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                             contentDescription = null)
                    }
                },
                shape = RoundedCornerShape(14.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { vm.setImageApiKey(apiKey); apiKey = "" },
                    enabled = !busy && apiKey.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Speichern") }
                TextButton(
                    onClick = { vm.deleteImageApiKey() },
                    enabled = !busy && cfg?.configured == true,
                ) { Text("Entfernen", color = MaterialTheme.colorScheme.error) }
                if (busy) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
            }
            msg?.let { m ->
                Text(m, style = MaterialTheme.typography.bodySmall,
                     color = if (m.startsWith("Fehler")) MaterialTheme.colorScheme.error
                             else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Key holen auf aistudio.google.com/apikey. Für Bild-Generation muss das " +
                "Cloud-Projekt einen Billing-Account haben (Free-Tier umfasst nur Text).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Cloud-Billing-Status-Widget: zeigt aktuellen Monats-Spend (Brutto-Schätzung
 * aus dem Cloud-TTS-Counter × Voice-Preis), den Budget-Hard-Cap und den
 * Restwert des AI-Pro-Credits.
 *
 * Wichtig: der Brutto-Spend ist eine SCHÄTZUNG, kein Live-Wert aus der
 * Cloud-Console. Google's Public-Billing-API liefert den echten Spend nicht
 * ohne BigQuery-Export. Für die UI-Anzeige „komme ich noch im Credit aus"
 * reicht die Schätzung allemal.
 */
@Composable
private fun CloudBillingWidget(vm: SettingsViewModel) {
    val billing by vm.billingStatus.collectAsState()
    val busy by vm.billingBusy.collectAsState()

    // Beim Composable-Mount einmal laden falls noch nicht da.
    LaunchedEffect(Unit) {
        if (vm.billingStatus.value == null) vm.refreshBillingStatus()
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Cloud-Verbrauch dieser Monat",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                InfoButton(title = "Cloud-Verbrauch — was zeigt das?") {
                    InfoParagraph(
                        "Hier siehst Du eine SCHÄTZUNG, wieviel Du diesen Monat über " +
                            "Cloud TTS verbraucht hast. Die Schätzung kommt aus dem " +
                            "Server-eigenen Zeichen-Zähler × Voice-Preis pro 1 Mio."
                    )
                    InfoParagraph(
                        "Der Wert ist BRUTTO — bevor Dein $10-AI-Pro-Credit angerechnet " +
                            "wird. Solange Brutto-Spend ≤ Credit-Restwert, landet am " +
                            "Monatsende NICHTS auf Deiner Kreditkarte."
                    )
                    InfoParagraph(
                        "Der genaue Stand steht in der Google Cloud Console unter " +
                            "Abrechnung — die Anzeige hier ist nur ein App-internes " +
                            "Hilfsmittel mit ggf. ein paar Cent Abweichung."
                    )
                }
                if (busy) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    IconButton(onClick = { vm.refreshBillingStatus() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Aktualisieren")
                    }
                }
            }

            val b = billing
            when {
                b == null -> {
                    Text(
                        "Lade Status …",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                !b.available -> {
                    Text(
                        b.error ?: "Cloud-Billing-Status nicht verfügbar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    val currency = b.currencyCode
                    val symbol = if (currency == "EUR") "€" else currency
                    fun fmt(v: Double): String = "%.2f %s".format(v, symbol)
                    val budgetTotal = b.budgetAmount ?: 0.0
                    val creditOrig = b.creditOriginal ?: 0.0
                    val spendNet = b.spendThisMonth.coerceAtLeast(0.0)
                    val creditUsed = minOf(spendNet, creditOrig)
                    val realCost = b.estimatedRealCost.coerceAtLeast(0.0)

                    // Hauptzeile: Brutto-Spend, prominent
                    Text(
                        "Diesen Monat: ${fmt(spendNet)} (Brutto, geschätzt)",
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    // Info-Hinweis (z.B. „Budget-API nicht aktiviert") — subtil,
                    // kein Error, nur kleiner Hinweis-Text in Akzent-Farbe.
                    b.warning?.takeIf { it.isNotBlank() }?.let { w ->
                        Text(
                            "ℹ $w",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Credit-Progress: wieviel vom AI-Pro-Credit ist schon „verbraucht"
                    if (creditOrig > 0.0) {
                        val pct = (creditUsed / creditOrig).coerceIn(0.0, 1.0).toFloat()
                        Column {
                            LinearProgressIndicator(
                                progress = { pct },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            val creditLeft = (b.creditRemaining ?: creditOrig).coerceAtLeast(0.0)
                            Text(
                                "AI-Pro-Credit verbleibend: ${fmt(creditLeft)} " +
                                    "von ${fmt(creditOrig)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Budget-Info (Hard Cap)
                    if (budgetTotal > 0.0) {
                        Text(
                            "Budget-Cap: ${b.budgetName ?: "Budget"} = ${fmt(budgetTotal)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Real-Cost-Hinweis: wenn über Credit raus → Warnung
                    if (realCost > 0.01) {
                        Text(
                            "⚠ Geschätzte Echtkosten am Monatsende: ${fmt(realCost)} " +
                                "(über Credit hinaus)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (creditOrig > 0.0) {
                        Text(
                            "✓ Im Credit-Rahmen — Kreditkarte wird nicht belastet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = de.smartzone.pocketclaude.ui.theme.PocketTheme.colors.success,
                        )
                    }

                    // Footer: Projekt-ID + last_updated für Debugging
                    val proj = b.projectId
                    val updated = b.lastUpdatedAt
                    if (proj != null || updated != null) {
                        Text(
                            buildString {
                                if (proj != null) append("Projekt: $proj")
                                if (proj != null && updated != null) append(" · ")
                                if (updated != null) append("Stand: ${updated.take(16)} UTC")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
