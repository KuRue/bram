package io.github.kurue.bram.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kurue.bram.core.domain.AcceleratorCapability
import io.github.kurue.bram.core.domain.AgentActivity
import io.github.kurue.bram.core.domain.CapabilityState
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.DeviceProfile
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.ModelProfile
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.RemoteEndpoint
import kotlinx.coroutines.launch
import java.util.Locale

/** Larger than any message can be tall, so scrolling clamps to the end of the transcript. */
private const val LARGE_SCROLL_OFFSET = 1_000_000

private enum class AppSection(val label: String, val glyph: String) {
    CHAT("Chat", "✦"),
    MODELS("Models", "▣"),
    SETTINGS("Settings", "⚙"),
}

/** Panels reachable from the menu, each shown as a sheet over the conversation. */
private enum class AppPanel { MODELS, SETTINGS }

@Composable
fun BramApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf<AppPanel?>(null) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }

    BackdropHost {
        // Applied once, at the root, so the whole shell lifts clear of the keyboard together.
        // Padding only the composer left it correct but the transcript underneath it, and padding
        // both moved the composer twice.
        Box(Modifier.fillMaxSize().imePadding()) {
            // Recorded: the field and the transcript, and nothing else. This is the frame every
            // panel blurs, which is why a reply scrolling past one goes soft rather than staying
            // sharp behind it. Panels are drawn after, outside the recording, so none of them ends
            // up blurring an image of itself.
            Box(Modifier.fillMaxSize().bramField().recordBackdrop()) {
                ChatTranscript(
                    state = state,
                    onRegenerate = viewModel::regenerateLastReply,
                    onEdit = viewModel::editAndResend,
                )
            }

            // A short fade over the status-bar strip only. The transcript still passes behind the
            // bubbles, which is the point, but stops colliding with the system clock and icons
            // where nothing can be done about the contrast.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(TOP_FADE_HEIGHT)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            TopBubbleBar(
                state = state,
                modifier = Modifier.align(Alignment.TopCenter),
                onMenu = { drawerOpen = true },
                onNewConversation = viewModel::startNewConversation,
                // With no models there is nothing to choose between, so the pill goes straight to
                // the place that fixes that.
                onPickModel = { panel = AppPanel.MODELS },
            )

            ChatComposer(
                state = state,
                modifier = Modifier.align(Alignment.BottomCenter),
                onSend = viewModel::send,
                onStop = viewModel::stopGeneration,
            )

            // The drawer and panels are drawn in this tree rather than in their own windows.
            // A separate window has nothing of the app behind it to sample, which is why the
            // Material sheet could not be frosted however it was configured.
            if (drawerOpen) {
                Scrim(onDismiss = { drawerOpen = false })
                BramDrawer(
                    state = state,
                    modifier = Modifier.align(Alignment.CenterStart),
                    onOpenConversation = {
                        viewModel.openConversation(it)
                        drawerOpen = false
                    },
                    onDeleteConversation = viewModel::deleteConversation,
                    onNewConversation = {
                        viewModel.startNewConversation()
                        drawerOpen = false
                    },
                    onOpenPanel = {
                        panel = it
                        drawerOpen = false
                    },
                )
            }

            panel?.let { open ->
                Scrim(onDismiss = { panel = null })
                GlassSurface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.88f),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    alpha = Glass.chromeAlpha,
                ) {
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                        Box(
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(vertical = 10.dp)
                                .size(width = 36.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                        )
                        when (open) {
                            AppPanel.MODELS -> ModelsScreen(
                                state = state,
                                onImport = { modelPicker.launch(arrayOf("*/*")) },
                                onSelect = viewModel::selectLocalModel,
                                onLoad = viewModel::loadModel,
                                onUnload = viewModel::unloadModel,
                                onRemove = viewModel::removeLocalModel,
                                onContext = viewModel::setPreferredContext,
                                onValidateAccelerator = viewModel::validateAccelerator,
                                onBisectAccelerator = viewModel::bisectAccelerator,
                                onReclaimStorage = viewModel::reclaimModelStorage,
                                onSelectBackend = viewModel::selectBackend,
                                onThinkingEnabled = viewModel::setThinkingEnabled,
                                onSelectProfile = viewModel::selectProfile,
                                onCreateProfile = viewModel::createProfile,
                                onUpdateProfile = viewModel::updateProfile,
                                onDeleteProfile = viewModel::deleteProfile,
                            )
                            AppPanel.SETTINGS -> SettingsScreen(
                                state = state,
                                onSaveEndpoint = viewModel::saveEndpoint,
                                onRemoveEndpoint = viewModel::removeEndpoint,
                                onRefreshDiagnostics = viewModel::refreshDeviceProfile,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Dismisses whatever is open, and dims what is behind it. */
@Composable
private fun BoxScope.Scrim(onDismiss: () -> Unit) {
    Box(
        Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
    )
}

/** Menu, live model state, and a fresh conversation — the three things wanted from any screen. */
@Composable
private fun TopBubbleBar(
    state: AppUiState,
    modifier: Modifier = Modifier,
    onMenu: () -> Unit,
    onNewConversation: () -> Unit,
    onPickModel: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BubbleButton(onClick = onMenu) {
            MenuIcon(MaterialTheme.colorScheme.onSurface, Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))

        GlassSurface(
            modifier = Modifier.weight(1f).clickable(onClick = onPickModel),
            shape = RoundedCornerShape(50),
            alpha = Glass.chromeAlpha,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.selectedLocalModel?.let { model -> state.profileFor(model).name }
                        ?: state.selectedEndpoint?.displayName
                        ?: "No model",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Text(
                    modelStatusLine(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.width(10.dp))
        BubbleButton(onClick = onNewConversation) {
            NewChatIcon(MaterialTheme.colorScheme.onSurface, Modifier.size(22.dp))
        }
    }
}

/** Live state belongs here: throughput while generating, otherwise what is loaded and where. */
@Composable
private fun modelStatusLine(state: AppUiState): String = when {
    state.isGenerating -> state.lastMetrics?.decodeTokensPerSecond
        ?.let { "${formatRate(it)} · generating" }
        ?: "generating…"
    state.selectedLocalModelIsLoaded -> buildString {
        append(state.loadedBackend?.label ?: "loaded")
        state.selectedLocalModel?.let { append(" · ${formatTokens(it.preferredContextTokens)}") }
        state.lastMetrics?.decodeTokensPerSecond?.let { append(" · ${formatRate(it)}") }
    }
    state.selectedEndpoint != null -> "Remote provider"
    state.selectedLocalModel != null -> "Not loaded"
    else -> "Import a model to begin"
}

/** Conversations, plus a way into the model and provider panels. */
@Composable
private fun BramDrawer(
    state: AppUiState,
    modifier: Modifier = Modifier,
    onOpenConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenPanel: (AppPanel) -> Unit,
) {
    GlassSurface(
        modifier = modifier.fillMaxHeight().widthIn(max = 320.dp),
        shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        alpha = Glass.chromeAlpha,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                BramDefaults.IDENTITY.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )

            GlassSurface(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onNewConversation),
                shape = RoundedCornerShape(Glass.cornerMedium),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NewChatIcon(MaterialTheme.colorScheme.onSurface, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("New conversation", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text(
                "Conversations",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.conversations.isEmpty()) {
                    item {
                        Text(
                            "Nothing yet. Ask Bram something.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
                items(state.conversations, key = { it.id.value }) { summary ->
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Glass.cornerMedium),
                        alpha = if (summary.id.value == state.activeConversationId) {
                            Glass.BUBBLE_ALPHA
                        } else {
                            Glass.DETAIL_ALPHA
                        },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier.weight(1f).clickable { onOpenConversation(summary.id.value) },
                            ) {
                                Text(
                                    summary.title.ifBlank { "Untitled" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                                Text(
                                    "${summary.messageCount} messages",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onDeleteConversation(summary.id.value) }) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "Models" to AppPanel.MODELS,
                    "Settings" to AppPanel.SETTINGS,
                ).forEach { (label, target) ->
                    GlassSurface(
                        modifier = Modifier.weight(1f).clickable { onOpenPanel(target) },
                        shape = RoundedCornerShape(Glass.cornerMedium),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Send while idle, stop while running.
 *
 * The ring is not decoration: local generation can stall for seconds between tokens on a phone, and
 * a static control gives no way to tell "still working" from "wedged". A slow continuous sweep says
 * the run is alive without implying progress towards a known end, which a determinate bar would.
 */
@Composable
private fun SendButton(
    generating: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "send")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
        ),
        label = "sweep",
    )
    val container = when {
        generating -> MaterialTheme.colorScheme.surfaceContainerHighest
        enabled -> MaterialTheme.colorScheme.primary
        // Still a filled circle when disabled, only quieter: a control that dissolves into the
        // composer gives no hint that it is the thing to press once there is something to send.
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val ring = MaterialTheme.colorScheme.primary

    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = generating || enabled) { if (generating) onStop() else onSend() },
        contentAlignment = Alignment.Center,
    ) {
        if (generating) {
            Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                drawArc(
                    color = ring,
                    startAngle = sweep,
                    sweepAngle = 90f,
                    useCenter = false,
                    style = Stroke(width = size.minDimension * 0.07f, cap = StrokeCap.Round),
                )
            }
            StopIcon(ring, Modifier.size(20.dp))
        } else {
            ArrowUpIcon(
                if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun BubbleButton(onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    GlassSurface(
        modifier = Modifier.size(44.dp).clickable(onClick = onClick),
        shape = CircleShape,
        alpha = Glass.chromeAlpha,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
private fun ChatTranscript(
    state: AppUiState,
    onRegenerate: () -> Unit,
    onEdit: (String, String) -> Unit,
) {
    val listState = rememberLazyListState()
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)

    // Follow the reply as it streams, keyed on the last message's length so each delta scrolls and
    // not merely each new message. The large offset scrolls past the item rather than aligning its
    // top, which matters because a reply is routinely taller than the viewport: aligning the top
    // would pin the screen to the opening line while the rest was written out of sight.
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(state.messages.lastIndex, LARGE_SCROLL_OFFSET) }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // Room for the chrome at both ends: the transcript passes behind the bars, but its first
        // and last lines must still be reachable rather than parked underneath them.
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = TOP_BAR_SPACE,
            bottom = COMPOSER_SPACE,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.messages.isEmpty()) {
            item {
                // One line, and only when there is nothing else to look at. The status pill already
                // says what is loaded, so this does not repeat it.
                Text(
                    when {
                        state.selectedLocalModelIsLoaded || state.selectedEndpoint != null ->
                            "Ask Bram anything."
                        state.localModels.isEmpty() -> "Import a model to begin."
                        else -> "Choose a model above to begin."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        items(state.messages, key = { it.id.value }) { message ->
            ChatBubble(
                message = message,
                canAct = !state.isGenerating,
                onCopy = { text ->
                    clipboard.setPrimaryClip(ClipData.newPlainText("Bram", text))
                },
                onRegenerate = onRegenerate,
                onEdit = { text -> onEdit(message.id.value, text) },
            )
        }
        state.status?.let { status ->
            item {
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        state.error?.let { error -> item { ErrorCard(error) } }
    }
}

@Composable
private fun ChatComposer(
    state: AppUiState,
    modifier: Modifier = Modifier,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }

    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp),
    ) {
        state.lastMetrics?.let { metrics ->
            Text(
                buildString {
                    append(state.loadedBackend?.label ?: "Runtime")
                    append(": ${formatRate(metrics.promptTokensPerSecond)} prompt")
                    append(" · ${formatRate(metrics.decodeTokensPerSecond)} generation")
                    metrics.processPssBytes?.let { append(" · ${formatBytes(it)} PSS") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )
        }

        GlassSurface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            shape = RoundedCornerShape(percent = 50),
            alpha = Glass.chromeAlpha,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A bare text field rather than OutlinedTextField: the latter reserves a 56dp touch
                // target and its own padding, which makes the composer taller than the pill needs
                // and pushes the text off centre.
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) {
                        Text(
                            "Message Bram",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isGenerating,
                        maxLines = 5,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.width(6.dp))
                SendButton(
                    generating = state.isGenerating,
                    enabled = input.isNotBlank() && (
                        state.selectedEndpoint != null || state.selectedLocalModelIsLoaded
                    ),
                    onSend = {
                        onSend(input)
                        input = ""
                    },
                    onStop = onStop,
                )
            }
        }
    }
}

/** Reserves the space the floating chrome occupies so the transcript is not trapped under it. */
private val TOP_BAR_SPACE = 76.dp
private val TOP_FADE_HEIGHT = 64.dp
private val COMPOSER_SPACE = 92.dp

@Composable
private fun RuntimeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(Glass.cornerMedium),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ModelsScreen(
    state: AppUiState,
    onImport: () -> Unit,
    onSelect: (String) -> Unit,
    onLoad: (String) -> Unit,
    onUnload: () -> Unit,
    onRemove: (String) -> Unit,
    onContext: (String, Int) -> Unit,
    onValidateAccelerator: (String, AcceleratorTarget) -> Unit,
    onBisectAccelerator: (String, AcceleratorTarget) -> Unit,
    onReclaimStorage: () -> Unit,
    onSelectBackend: (String, RuntimeBackend) -> Unit,
    onThinkingEnabled: (String, Boolean) -> Unit,
    onSelectProfile: (String) -> Unit,
    onCreateProfile: (LocalModelRecord) -> Unit,
    onUpdateProfile: (ModelProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
) {
    var expandedModelId by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Local models", "Imported and SHA-256 verified", Modifier.weight(1f))
                Button(onClick = onImport, enabled = !state.isImporting && !state.isGenerating) { Text("Import GGUF") }
            }
        }
        if (state.isImporting) {
            item {
                GlassSurface(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(state.importProgress?.stage ?: "Importing model", fontWeight = FontWeight.SemiBold)
                            state.importProgress?.takeIf { it.totalBytes > 0 }?.let {
                                Text(
                                    "${formatBytes(it.bytesRead)} / ${formatBytes(it.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (state.localModels.isEmpty() && !state.isImporting) {
            item {
                GlassSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No local models yet", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Pick a GGUF from this device. Bram copies it into its own storage so the " +
                                "native runtime can load it, then verifies the copy with SHA-256.",
                        )
                        Button(onClick = onImport) { Text("Choose a GGUF") }
                    }
                }
            }
        }
        items(state.localModels, key = { it.id.value }) { model ->
            LocalModelCard(
                model = model,
                expanded = expandedModelId == model.id.value,
                selected = state.selectedRuntimeId == model.id.value,
                loaded = state.loadedModelId == model.id.value && state.cpuValidated,
                loading = state.isLoadingModel && state.selectedRuntimeId == model.id.value,
                generationActive = state.isGenerating,
                availableBackends = state.availableBackends,
                backend = state.backendFor(model),
                profiles = state.profiles.filter { it.modelId == model.id },
                activeProfile = state.profileFor(model),
                onSelectProfile = onSelectProfile,
                onCreateProfile = { onCreateProfile(model) },
                onUpdateProfile = onUpdateProfile,
                onDeleteProfile = onDeleteProfile,
                onToggleExpanded = {
                    expandedModelId = if (expandedModelId == model.id.value) null else model.id.value
                },
                onSelectBackend = { chosen -> onSelectBackend(model.id.value, chosen) },
                onSelect = { onSelect(model.id.value) },
                onLoad = { onLoad(model.id.value) },
                onUnload = onUnload,
                onRemove = { onRemove(model.id.value) },
                onContext = { onContext(model.id.value, it) },
                onThinking = { onThinkingEnabled(model.id.value, it) },
            )
        }
        state.modelLoadDetail?.let { detail -> item { InfoCard("Active runtime", detail) } }
        if (state.modelStorageBytes > 0) {
            item {
                GlassSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Model storage", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Imported models are copied into Bram's private storage, which currently " +
                                "holds ${formatBytes(state.modelStorageBytes)}. Copies left behind by an " +
                                "interrupted import can be removed safely.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onReclaimStorage) { Text("Remove unreferenced copies") }
                    }
                }
            }
        }
        state.selectedLocalModel?.let { model ->
            item {
                AcceleratorValidationCard(
                    state = state,
                    onValidate = { chosen -> onValidateAccelerator(model.id.value, chosen) },
                    onBisect = { chosen -> onBisectAccelerator(model.id.value, chosen) },
                )
            }
        }
        state.error?.let { error -> item { ErrorCard(error) } }
        item {
            Text(
                "LiteRT, model downloads, and storage-assisted oversized models are not implemented yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AcceleratorValidationCard(
    state: AppUiState,
    onValidate: (AcceleratorTarget) -> Unit,
    onBisect: (AcceleratorTarget) -> Unit,
) {
    // Only offer accelerators this build actually found. Otherwise selecting one fails with
    // "no device", which reads as a bug rather than as an absent backend.
    val targets = AcceleratorTarget.entries.filter { candidate ->
        state.availableBackends.any { backend ->
            backend.offloadsToAccelerator && backend.devicePrefix == candidate.devicePrefix
        }
    }
    var target by rememberSaveable { mutableStateOf(targets.firstOrNull() ?: AcceleratorTarget.VULKAN) }
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Accelerator validation", fontWeight = FontWeight.SemiBold)
            Text(
                "Records a deterministic CPU reference, then feeds the same tokens to the accelerator " +
                    "and compares each next-token prediction. Teacher forcing keeps one difference " +
                    "from cascading, so the score reflects compute accuracy rather than drift.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (targets.isEmpty()) {
                Text(
                    "This build found no accelerator to compare against CPU.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { option ->
                    FilterChip(
                        selected = target == option,
                        onClick = { target = option },
                        label = { Text(option.label) },
                    )
                }
            }
            val busy = state.isValidatingAccelerator || state.isLoadingModel || state.isGenerating
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onValidate(target) },
                    enabled = !busy && targets.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isValidatingAccelerator) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (state.isValidatingAccelerator) "Working…" else "Compare ${target.label} to CPU",
                    )
                }
                OutlinedButton(
                    onClick = { onBisect(target) },
                    enabled = !busy && targets.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Bisect layers to find where it breaks") }
            }
            state.status?.takeIf { state.isValidatingAccelerator }?.let { status ->
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            state.acceleratorBisection?.let { bisection ->
                HorizontalDivider()
                Text("Layer bisection", fontWeight = FontWeight.SemiBold)
                Text(bisection.detail, style = MaterialTheme.typography.bodySmall)
                bisection.probes.forEach { probe ->
                    Text(
                        "${if (probe.usable) "AGREES" else "DIFFERS"} · ${probe.gpuLayers} layers · " +
                            "%.0f%% · ".format(probe.agreement * 100) + "${probe.millis} ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (probe.usable) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            state.acceleratorReport?.let { report ->
                HorizontalDivider()
                Text(
                    if (report.matchesCpu) {
                        "Validated · %.0f%% agreement".format(report.agreement * 100)
                    } else {
                        "Not validated · %.0f%% agreement".format(report.agreement * 100)
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = if (report.matchesCpu) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(report.detail, style = MaterialTheme.typography.bodySmall)
                Text(
                    "CPU ${report.cpuMillis} ms · ${report.deviceName} ${report.acceleratorMillis} ms" +
                        if (report.speedup > 0) " · %.2f× ".format(report.speedup) + "vs CPU" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!report.matchesCpu) {
                    Text(
                        "CPU: ${report.cpuText.take(120)}\nGPU: ${report.acceleratorText.take(120)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalModelCard(
    model: LocalModelRecord,
    expanded: Boolean,
    selected: Boolean,
    loaded: Boolean,
    loading: Boolean,
    generationActive: Boolean,
    availableBackends: List<RuntimeBackend>,
    backend: RuntimeBackend,
    profiles: List<ModelProfile>,
    activeProfile: ModelProfile,
    onSelectProfile: (String) -> Unit,
    onCreateProfile: () -> Unit,
    onUpdateProfile: (ModelProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onToggleExpanded: () -> Unit,
    onSelectBackend: (RuntimeBackend) -> Unit,
    onSelect: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onRemove: () -> Unit,
    onContext: (Int) -> Unit,
    onThinking: (Boolean) -> Unit,
) {
    val locked = loaded || loading || generationActive
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Collapsed header: identity and status only. Everything else is opt-in, so a phone
            // with several models does not present a wall of chips.
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${model.architecture} · ${model.quantization} · ${formatBytes(model.fileSizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (loaded) {
                        Text(
                            "● Loaded",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            backend.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            "${backend.label} · ${formatTokens(model.preferredContextTokens)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (expanded) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!model.hasChatTemplate) {
                Text(
                    "No chat template found, so this model cannot chat yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Primary action stays visible while collapsed: loading is the common task.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loaded) {
                    Button(onClick = onUnload, enabled = !generationActive) { Text("Unload") }
                } else {
                    Button(
                        onClick = {
                            onSelect()
                            onLoad()
                        },
                        enabled = !loading && !generationActive && model.hasChatTemplate,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Load on ${backend.label}")
                        }
                    }
                }
                if (!selected && !loaded) {
                    OutlinedButton(onClick = onSelect) { Text("Use in chat") }
                }
            }

            if (expanded) {
                HorizontalDivider()
                if (availableBackends.size > 1) {
                    Text("Run on", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        availableBackends.forEach { option ->
                            FilterChip(
                                selected = backend == option,
                                onClick = { onSelectBackend(option) },
                                enabled = !generationActive,
                                label = { Text(option.label) },
                            )
                        }
                    }
                    Text(
                        "Remembered for this model. Changing it unloads the model first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ProfilePicker(
                    profiles = profiles,
                    activeProfile = activeProfile,
                    // Not `locked`: naming a profile changes nothing the runtime is using, and
                    // switching to another deliberately unloads rather than being forbidden.
                    locked = generationActive,
                    onSelectProfile = onSelectProfile,
                    onCreateProfile = onCreateProfile,
                    onUpdateProfile = onUpdateProfile,
                    onDeleteProfile = onDeleteProfile,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Reasoning", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Let the model think before answering. Thorough but much slower, and " +
                                "short questions rarely need it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(
                        checked = activeProfile.thinkingEnabled,
                        onCheckedChange = { onThinking(it) },
                        enabled = !generationActive,
                    )
                }

                Text("Context", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    contextOptions(model).forEach { tokens ->
                        FilterChip(
                            selected = tokens == activeProfile.contextTokens,
                            onClick = { onContext(tokens) },
                            enabled = !locked,
                            label = { Text(formatTokens(tokens)) },
                        )
                    }
                }

                SamplerControls(
                    profile = activeProfile,
                    enabled = !generationActive,
                    onUpdateProfile = onUpdateProfile,
                )

                Text(
                    "${model.layerCount.takeIf { it > 0 } ?: "?"} layers · trained context " +
                        "${formatTokens(model.trainedContextTokens)} · SHA ${model.sha256.take(12)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRemove, enabled = !locked) { Text("Remove model") }
            }
        }
    }
}

/**
 * Chooses which saved configuration a model runs under, and names it.
 *
 * Profiles are how one GGUF becomes several usable things — a long-context one, a fast one, a
 * reasoning one — so the picker sits above the settings it decides the owner of. There is always at
 * least one, created on import, which is why deleting the last is not offered.
 */
@Composable
private fun ProfilePicker(
    profiles: List<ModelProfile>,
    activeProfile: ModelProfile,
    locked: Boolean,
    onSelectProfile: (String) -> Unit,
    onCreateProfile: () -> Unit,
    onUpdateProfile: (ModelProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
) {
    var renaming by rememberSaveable(activeProfile.id) { mutableStateOf(false) }
    var draftName by rememberSaveable(activeProfile.id) { mutableStateOf(activeProfile.name) }

    Text("Profile", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        profiles.forEach { profile ->
            FilterChip(
                selected = profile.id == activeProfile.id,
                onClick = { onSelectProfile(profile.id) },
                enabled = !locked,
                label = { Text(profile.name) },
            )
        }
        FilterChip(
            selected = false,
            onClick = onCreateProfile,
            enabled = !locked,
            label = { Text("+ New") },
        )
    }

    var editingPrompt by rememberSaveable(activeProfile.id) { mutableStateOf(false) }
    var draftPrompt by rememberSaveable(activeProfile.id) { mutableStateOf(activeProfile.systemPrompt) }

    // Renaming is safe at any time; it is the only edit here that the runtime never reads.
    if (renaming) {
        OutlinedTextField(
            value = draftName,
            onValueChange = { draftName = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Profile name") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    // A profile with no name is unpickable in the chip row, so an empty field keeps
                    // the old one rather than producing a blank chip.
                    draftName.trim().takeIf(String::isNotEmpty)?.let { name ->
                        onUpdateProfile(activeProfile.copy(name = name))
                    }
                    renaming = false
                },
            ) { Text("Save") }
            TextButton(onClick = { renaming = false }) { Text("Cancel") }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = { renaming = true }) { Text("Rename") }
            TextButton(onClick = { editingPrompt = !editingPrompt }) {
                Text(if (activeProfile.systemPrompt.isBlank()) "Add instructions" else "Instructions")
            }
            if (profiles.size > 1) {
                TextButton(
                    onClick = { onDeleteProfile(activeProfile.id) },
                    enabled = !locked,
                ) { Text("Delete profile") }
            }
        }
    }

    if (editingPrompt) {
        OutlinedTextField(
            value = draftPrompt,
            onValueChange = { draftPrompt = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8,
            label = { Text("Instructions for this profile") },
        )
        Text(
            "Added to Bram's own instructions rather than replacing them, so a profile can change " +
                "how Bram answers without dropping the rules that keep a run honest.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onUpdateProfile(activeProfile.copy(systemPrompt = draftPrompt.trim()))
                    editingPrompt = false
                },
            ) { Text("Save") }
            TextButton(onClick = {
                draftPrompt = activeProfile.systemPrompt
                editingPrompt = false
            }) { Text("Cancel") }
        }
    }
}

/**
 * The sampling settings, which until now were fixed in the runtime.
 *
 * Temperature reads as "how much it wanders" rather than by name, because the number means nothing
 * to most people and zero means something specific: the same answer every time.
 */
@Composable
private fun SamplerControls(
    profile: ModelProfile,
    enabled: Boolean,
    onUpdateProfile: (ModelProfile) -> Unit,
) {
    val sampler = profile.sampler
    Text("Sampling", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    Text(
        if (sampler.isGreedy) {
            "Temperature 0 — always picks the likeliest word, so the same question gives the " +
                "same answer."
        } else {
            "Temperature ${"%.2f".format(sampler.temperature)} — higher wanders further from the " +
                "likeliest word."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = sampler.temperature,
        onValueChange = { onUpdateProfile(profile.copy(sampler = sampler.copy(temperature = it))) },
        valueRange = 0f..2f,
        enabled = enabled,
    )

    Text(
        "Repetition penalty ${"%.2f".format(sampler.repeatPenalty)} — higher discourages the model " +
            "from repeating itself, which a small model on a short context tends to do.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = sampler.repeatPenalty,
        onValueChange = { onUpdateProfile(profile.copy(sampler = sampler.copy(repeatPenalty = it))) },
        valueRange = 1f..2f,
        enabled = enabled,
    )

    Text(
        "Top-p ${"%.2f".format(sampler.topP)} · top-k ${sampler.topK}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = sampler.topP,
        onValueChange = { onUpdateProfile(profile.copy(sampler = sampler.copy(topP = it))) },
        valueRange = 0.05f..1f,
        enabled = enabled,
    )
}

@Composable
private fun SettingsScreen(
    state: AppUiState,
    onSaveEndpoint: (EndpointDraft) -> Unit,
    onRemoveEndpoint: (String) -> Unit,
    onRefreshDiagnostics: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("http://127.0.0.1:11434/v1") }
    var modelName by rememberSaveable { mutableStateOf("") }
    var context by rememberSaveable { mutableStateOf("32768") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var allowHttp by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Optional remote providers", "OpenAI-compatible Chat Completions endpoints")
        if (state.endpoints.isEmpty()) Text("None configured. Local GGUF chat does not require one.")
        state.endpoints.forEach { EndpointCard(it, onRemoveEndpoint) }

        Text("Add provider", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(modelName, { modelName = it }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            context,
            { context = it.filter(Char::isDigit) },
            label = { Text("Context tokens") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            apiKey,
            { apiKey = it },
            label = { Text("API key (optional)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(allowHttp, { allowHttp = it })
            Column {
                Text("Allow insecure HTTP")
                Text(
                    "Only for a trusted local network; prompts and credentials are unencrypted in transit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = {
                onSaveEndpoint(
                    EndpointDraft(name, baseUrl, modelName, context.toIntOrNull() ?: 0, apiKey, allowHttp),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save provider") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(
            "Device diagnostics",
            "Detected means the runtime found it, not that it computes correctly",
            Modifier.weight(1f),
        )
            TextButton(onClick = onRefreshDiagnostics) { Text("Refresh") }
        }
        state.deviceProfile?.let { profile ->
            DeviceSummaryCard(profile)
            profile.accelerators.forEach { AcceleratorRow(it) }
        } ?: CircularProgressIndicator()

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("Agent foundation", "Kept behind the local model experience")
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadinessRow("Context budgeting", "Working")
                ReadinessRow("Remote tool loop", "Working")
                ReadinessRow(
                    "Local isolated inference",
                    if (state.cpuValidated) {
                        "Running on ${state.loadedBackend?.label ?: "this device"}"
                    } else {
                        "Awaiting model load"
                    },
                )
                ReadinessRow("Durable conversation memory", "Deferred")
                ReadinessRow("Skills and automation", "Deferred")
            }
        }
        state.error?.let { ErrorCard(it) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ChatBubble(
    message: ConversationMessage,
    canAct: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onEdit: (String) -> Unit,
) {
    if (message.role == MessageRole.SYSTEM) return
    val isUser = message.role == MessageRole.USER
    var editing by rememberSaveable(message.id.value) { mutableStateOf(false) }
    var draft by rememberSaveable(message.id.value) { mutableStateOf(message.content) }
    var showActions by rememberSaveable(message.id.value) { mutableStateOf(false) }

    // Assistant replies run the full width: they are long, often contain code, and a tinted
    // container around several paragraphs makes them harder to read, not easier. Only the user's
    // own turns are enclosed, and that alternation is what marks who is speaking — a "You"/"Bram"
    // label above every turn says the same thing again in more furniture.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(enabled = !editing) { showActions = !showActions },
    ) {
        if (isUser) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Glass.cornerMedium),
                alpha = Glass.BUBBLE_ALPHA,
                tint = MaterialTheme.colorScheme.primaryContainer,
                blur = false,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    MessageBody(message, editing, draft, { draft = it }) {
                        editing = false
                        onEdit(draft)
                    }
                }
            }
        } else {
            MessageBody(message, editing, draft, { draft = it }) {
                editing = false
                onEdit(draft)
            }
        }

        // Revealed on tap rather than always present. Two rows of buttons under every message is
        // a lot of furniture in a long transcript, and these are occasional actions.
        if (canAct && showActions && !editing && message.content.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { onCopy(message.content) }) { Text("Copy") }
                if (isUser) {
                    TextButton(onClick = {
                        draft = message.content
                        editing = true
                    }) { Text("Edit") }
                } else {
                    TextButton(onClick = onRegenerate) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun MessageBody(
    message: ConversationMessage,
    editing: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    message.activity.forEach { entry -> ActivityRow(entry) }
    if (editing) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 6,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSubmit) { Text("Send") }
        }
    } else if (message.content.isNotBlank() || message.activity.isEmpty()) {
        // Models answer in Markdown whether or not anyone asked them to, so rendering it is closer
        // to showing the reply than showing the raw characters is.
        Text(
            renderMarkdown(message.content.ifBlank { "…" }),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * One line of agent work, collapsed by default.
 *
 * Reasoning and tool traffic are worth keeping and occasionally worth reading, but shown inline
 * they bury the answer — a small model can spend several paragraphs deciding how to say hello.
 */
@Composable
private fun ActivityRow(entry: AgentActivity) {
    var expanded by rememberSaveable(entry.summary) { mutableStateOf(false) }
    val detail = when (entry) {
        is AgentActivity.Thinking -> entry.text
        is AgentActivity.ToolInvocation -> buildString {
            append(entry.argumentsJson)
            entry.result?.let { result ->
                appendLine()
                appendLine()
                append(result)
            }
        }
    }
    val glyph = when (entry) {
        is AgentActivity.Thinking -> "✳"
        is AgentActivity.ToolInvocation -> if (entry.result == null) "◌" else "⚙"
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = detail.isNotBlank()) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$glyph ${entry.summary}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (detail.isNotBlank()) {
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded && detail.isNotBlank()) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = RoundedCornerShape(Glass.cornerMedium),
                alpha = Glass.DETAIL_ALPHA,
                blur = false,
            ) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceSummaryCard(profile: DeviceProfile) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("${profile.manufacturer} ${profile.model}", style = MaterialTheme.typography.titleMedium)
            Text(profile.soc, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            MetricRow("RAM available", "${formatBytes(profile.availableRamBytes)} / ${formatBytes(profile.totalRamBytes)}")
            MetricRow("Storage free", "${formatBytes(profile.freeStorageBytes)} / ${formatBytes(profile.totalStorageBytes)}")
            MetricRow("CPU", "${profile.cpuCoreCount} cores · ${profile.appAbi}")
            MetricRow("Thermals", profile.thermalStatus)
        }
    }
}

@Composable
private fun AcceleratorRow(capability: AcceleratorCapability) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Glass.cornerMedium),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            val marker = when (capability.state) {
                CapabilityState.AVAILABLE -> "✓"
                CapabilityState.DETECTED_NOT_VALIDATED -> "?"
                CapabilityState.UNPROBED -> "·"
                CapabilityState.UNAVAILABLE, CapabilityState.FAILED_VALIDATION -> "×"
            }
            Text(marker, modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold)
            Column {
                Text(capability.kind.name.replace('_', ' '), fontWeight = FontWeight.Medium)
                Text(capability.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EndpointCard(endpoint: RemoteEndpoint, onRemove: (String) -> Unit) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(endpoint.displayName, fontWeight = FontWeight.SemiBold)
                Text(endpoint.modelName)
                Text(
                    "${endpoint.baseUrl} · ${formatTokens(endpoint.contextWindowTokens)} context",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onRemove(endpoint.id) }) { Text("Remove") }
        }
    }
}

@Composable
private fun InfoCard(title: String, detail: String) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ErrorCard(error: String) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Glass.cornerMedium),
        // Shown inside the transcript as well as in panels, and anything inside the recorded
        // backdrop must not blur it.
        blur = false,
        tint = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ReadinessRow(label: String, status: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    }
}

private fun contextOptions(model: LocalModelRecord): List<Int> {
    val maximum = model.trainedContextTokens.takeIf { it > 0 } ?: model.preferredContextTokens
    val standard = listOf(1_024, 2_048, 4_096, 8_192, 16_384, 32_768, 65_536, 131_072)
    return (standard.filter { it <= maximum } + maximum + model.preferredContextTokens)
        .filter { it >= 256 }
        .distinct()
        .sorted()
}

private fun formatBytes(bytes: Long): String {
    val gib = bytes / 1_073_741_824.0
    return if (gib >= 1) String.format(Locale.US, "%.1f GB", gib)
    else String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
}

private fun formatTokens(tokens: Int): String = when {
    tokens <= 0 -> "unknown"
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> String.format(Locale.US, "%.0fK", tokens / 1_000.0)
    else -> tokens.toString()
}

private fun formatRate(rate: Double?): String = rate?.let { String.format(Locale.US, "%.1f tok/s", it) } ?: "measuring"
