package io.github.kurue.bram.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.platform.LocalLocale
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.kurue.bram.core.domain.AcceleratorCapability
import io.github.kurue.bram.core.domain.AgentActivity
import org.json.JSONObject
import io.github.kurue.bram.core.domain.Automation
import io.github.kurue.bram.core.domain.isHybridArchitecture
import io.github.kurue.bram.core.domain.CapabilityState
import io.github.kurue.bram.core.domain.BackendMeasurement
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.DeviceProfile
import io.github.kurue.bram.core.domain.FlashAttentionMode
import io.github.kurue.bram.core.domain.HexFlags
import io.github.kurue.bram.core.domain.KvCacheType
import io.github.kurue.bram.core.domain.LoadMode
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.ModelProfile
import io.github.kurue.bram.core.domain.TuningDimension
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.MemoryKind
import io.github.kurue.bram.core.domain.MemoryRecord
import io.github.kurue.bram.core.domain.PermissionMode
import io.github.kurue.bram.core.domain.PrivacyClass
import io.github.kurue.bram.core.domain.RemoteApiKind
import io.github.kurue.bram.core.domain.RoutingMode
import io.github.kurue.bram.core.domain.RoutingPoolSlot
import io.github.kurue.bram.core.domain.RemoteEndpoint
import io.github.kurue.bram.core.domain.RunJournalEntry
import io.github.kurue.bram.core.domain.RunStatus
import io.github.kurue.bram.core.domain.SkillPackage
import io.github.kurue.bram.core.domain.ToolApprovalDecision
import io.github.kurue.bram.core.domain.displayName
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
private enum class AppPanel {
    MODELS,
    ROUTING,
    SETTINGS,
    CAPABILITIES,
    PROVIDERS,
    TOOLS,
    SKILLS,
    AUTOMATIONS,
    MEMORIES,
    SYSTEM,
    SESSION,
    TASKS,
}

@Composable
fun BramApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf<AppPanel?>(null) }
    var displayedPanel by rememberSaveable { mutableStateOf<AppPanel?>(null) }
    LaunchedEffect(panel) { panel?.let { displayedPanel = it } }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }
    val skillPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importSkillDocument)
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // The toggle works either way; without the permission the completion alert is simply
        // never posted, which the settings screen says.
    }
    val runtimePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.resolvedRuntimePermission(granted)
    }
    LaunchedEffect(state.requestNotificationPermission) {
        if (state.requestNotificationPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            viewModel.consumedPermissionRequest()
        }
    }
    // A tool call that was approved but needs a runtime permission shows the system dialog here;
    // the answer is fed back through the broker so the tool either runs or returns a denial.
    LaunchedEffect(state.runtimePermissionRequest) {
        state.runtimePermissionRequest?.let { permission ->
            runtimePermission.launch(permission)
            viewModel.consumedRuntimePermissionRequest()
        }
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
                GenerationLattice(
                    generating = state.isGenerating,
                    modifier = Modifier.fillMaxSize(),
                )
                ChatTranscript(
                    state = state,
                    onRegenerate = viewModel::regenerateLastReply,
                    onEdit = viewModel::editAndResend,
                    onResolveApproval = viewModel::resolveApproval,
                )
            }

            // A short fade over the status-bar strip only. The transcript still passes behind the
            // bubbles, which is the point, but stops colliding with the system clock and icons
            // where nothing can be done about the contrast.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(
                        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                            TOP_FADE_HEIGHT,
                    )
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
                onPickModel = { panel = if (state.profiles.isEmpty() && state.endpoints.isEmpty()) AppPanel.MODELS else AppPanel.ROUTING },
            )

            ChatComposer(
                state = state,
                modifier = Modifier.align(Alignment.BottomCenter),
                onSend = viewModel::send,
                onStop = viewModel::stopGeneration,
            )

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

            if (panel != null) {
                Scrim(onDismiss = { panel = null })
            }
            AnimatedVisibility(
                visible = panel != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                displayedPanel?.let { open ->
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.88f),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    alpha = Glass.chromeAlpha,
                    // Darker than the cards it holds. Both drew from the same default before, so
                    // lightening the cards lightened their backdrop with them and nothing separated.
                    tint = Glass.panelTint,
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
                                onReclaimStorage = viewModel::reclaimModelStorage,
                                onCreateProfile = viewModel::createProfile,
                                onUpdateProfile = viewModel::updateProfile,
                                onDeleteProfile = viewModel::deleteProfile,
                                onSaveEndpoint = viewModel::saveEndpoint,
                                onRemoveEndpoint = viewModel::removeEndpoint,
                                onAutoConfigure = viewModel::autoConfigure,
                                onTuneBatch = viewModel::tuneBatch,
                                onTuneDimension = viewModel::tuneDimension,
                                tuningDimension = state.tuningDimension,
                            )
                            AppPanel.ROUTING -> RoutingSummaryScreen(
                                state = state,
                                onAssign = viewModel::setRoutingPoolTarget,
                                onManageProfiles = { panel = AppPanel.MODELS },
                                onConversationDetails = { panel = AppPanel.SESSION },
                                onSetRoutingMode = viewModel::setRoutingMode,
                                onSetDefaultPrivacyClass = viewModel::setDefaultPrivacyClass,
                            )
                            AppPanel.SETTINGS -> SettingsScreen(
                                state = state,
                                onOpenPanel = { panel = it },
                                onSetCompletionAlerts = viewModel::setCompletionAlerts,
                            )
                            AppPanel.CAPABILITIES -> CapabilitiesScreen(
                                state = state,
                                onOpenPanel = { panel = it },
                            )
                            AppPanel.PROVIDERS -> ProvidersScreen(
                                state = state,
                                onBack = { panel = AppPanel.CAPABILITIES },
                                onSaveEndpoint = viewModel::saveEndpoint,
                                onRemoveEndpoint = viewModel::removeEndpoint,
                            )
                            AppPanel.TOOLS -> ToolsScreen(
                                state = state,
                                onBack = { panel = AppPanel.CAPABILITIES },
                                onSaveMcpServer = viewModel::saveMcpServer,
                                onRemoveMcpServer = viewModel::removeMcpServer,
                                onRefreshMcpServers = viewModel::refreshMcpServers,
                                onWithdrawToolPermission = viewModel::withdrawToolPermission,
                            )
                            AppPanel.SKILLS -> SkillsScreen(
                                state = state,
                                onBack = { panel = AppPanel.CAPABILITIES },
                                onImportSkill = { skillPicker.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream", "*/*")) },
                                onSaveSkill = viewModel::importSkill,
                                onActivateSkillDraft = viewModel::activateSkillDraft,
                                onRollbackSkill = viewModel::rollbackSkill,
                                onRemoveSkill = viewModel::removeSkill,
                            )
                            AppPanel.AUTOMATIONS -> AutomationsScreen(
                                state = state,
                                onBack = { panel = AppPanel.CAPABILITIES },
                                onSaveAutomation = viewModel::saveAutomation,
                                onRemoveAutomation = viewModel::removeAutomation,
                                onSetAutomationEnabled = viewModel::setAutomationEnabled,
                            )
                            AppPanel.MEMORIES -> MemoriesScreen(
                                state = state,
                                onBack = { panel = AppPanel.CAPABILITIES },
                                onRemoveMemory = viewModel::removeMemory,
                                onSetEmbeddingModel = viewModel::setEmbeddingModel,
                                onClearEmbeddingModel = viewModel::clearEmbeddingModel,
                            )
                            AppPanel.SYSTEM -> SystemScreen(
                                state = state,
                                onBack = { panel = AppPanel.SETTINGS },
                                onRefreshDiagnostics = viewModel::refreshDeviceProfile,
                            )
                            AppPanel.SESSION -> SessionScreen(
                                state = state,
                                onSetMode = viewModel::updatePermissionMode,
                                onSetPrivacyClass = viewModel::updateConversationPrivacyClass,
                            )
                            AppPanel.TASKS -> TasksScreen(
                                state = state,
                                onEnqueue = viewModel::enqueueTask,
                                onCancel = viewModel::cancelTask,
                                onRetry = viewModel::retryTask,
                                onDelete = viewModel::deleteTask,
                            )
                        }
                    }
                }
                }
            }

            // Configuration must sit above the profile panel that started it. It used to be
            // composed before panels, leaving the running/result overlay hidden underneath one.
            state.autoConfigure?.let { progress ->
                AutoConfigureOverlay(progress, onDismiss = viewModel::dismissAutoConfigure)
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
        BubbleButton(onClick = onMenu, modifier = Modifier.testTag("menu-button")) {
            MenuIcon(MaterialTheme.colorScheme.onSurface, Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))

        GlassSurface(
            modifier = Modifier.weight(1f).clickable(onClick = onPickModel).testTag("model-pill"),
            shape = RoundedCornerShape(50),
            alpha = Glass.chromeAlpha,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.routingTargetLabel(state.routingPool.primaryTargetId)
                        ?: state.lastRoutedRuntimeLabel
                        ?: state.selectedLocalModel?.let { model -> state.profileFor(model).name }
                        ?: state.selectedEndpoint?.displayName
                        ?: "Add a profile",
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
        BubbleButton(onClick = onNewConversation, modifier = Modifier.testTag("new-chat")) {
            NewChatIcon(MaterialTheme.colorScheme.onSurface, Modifier.size(22.dp))
        }
    }
}

/** Live state belongs here: throughput while generating, otherwise what is loaded and where. */
@Composable
private fun modelStatusLine(state: AppUiState): String = when {
    // Throughput belongs here rather than above the composer: it is state about the model, which is
    // what this line is for, and the chat says it is working by animating instead.
    state.isGenerating -> buildString {
        append(state.loadedBackend?.label ?: "Running")
        state.selectedLocalModel?.let { append(" · ${formatTokens(it.preferredContextTokens)}") }
        state.lastMetrics?.decodeTokensPerSecond?.let { append(" · ${formatRate(it)}") }
    }
    state.routingMode == RoutingMode.AUTO && !state.routingPool.isEmpty ->
        state.routingTargetLabel(state.routingPool.primaryTargetId)?.let { "Primary · ready" }
            ?: "Choose a Primary profile"
    state.selectedLocalModelIsLoaded -> buildString {
        append(state.loadedBackend?.label ?: "loaded")
        state.selectedLocalModel?.let { append(" · ${formatTokens(it.preferredContextTokens)}") }
        state.lastMetrics?.decodeTokensPerSecond?.let { append(" · ${formatRate(it)}") }
    }
    state.selectedEndpoint != null -> "Ready"
    state.selectedLocalModel != null -> "Not loaded"
    else -> "Add a profile to begin"
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
            Column(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    listOf("Models" to AppPanel.MODELS, "Tasks" to AppPanel.TASKS),
                    listOf("Capabilities" to AppPanel.CAPABILITIES, "Settings" to AppPanel.SETTINGS),
                    // The per-conversation tool-approval mode and privacy class live here; before
                    // the drawer carried them they were only reachable through the top pill's
                    // routing screen, which made "don't ask for this conversation" undiscoverable.
                    listOf("Conversation" to AppPanel.SESSION, "System" to AppPanel.SYSTEM),
                ).forEach { destinations ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        destinations.forEach { (label, target) ->
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
            .clickable(enabled = generating || enabled) { if (generating) onStop() else onSend() }
            .testTag("send-button"),
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
private fun BubbleButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier.size(44.dp).clickable(onClick = onClick),
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
    onResolveApproval: (ToolApprovalDecision) -> Unit,
) {
    val listState = rememberLazyListState()
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)

    // Follow the reply as it streams, keyed on the last message's length so each delta scrolls and
    // not merely each new message. The large offset scrolls past the item rather than aligning its
    // top, which matters because a reply is routinely taller than the viewport: aligning the top
    // would pin the screen to the opening line while the rest was written out of sight.
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length, state.pendingApproval) {
        when {
            // The approval card sits just past the last message, so a long thread leaves it hidden
            // under the composer or off the bottom. Bring it into view the moment it appears,
            // otherwise the run blocks on a prompt the user cannot see.
            state.pendingApproval != null && state.messages.isNotEmpty() ->
                runCatching { listState.animateScrollToItem(state.messages.size, LARGE_SCROLL_OFFSET) }
            state.messages.isNotEmpty() ->
                runCatching { listState.animateScrollToItem(state.messages.lastIndex, LARGE_SCROLL_OFFSET) }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // Room for the chrome at both ends: the transcript passes behind the bars, but its first
        // and last lines must still be reachable rather than parked underneath them. The top bar
        // sits below the status bar, so its inset counts too — a fixed figure left the first
        // message touching the bubbles on a phone with a taller status bar.
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TOP_BAR_SPACE,
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
                        !state.routingPool.isEmpty -> "Ask Bram anything."
                        state.localModels.isEmpty() && state.endpoints.isEmpty() ->
                            "Add a local or remote profile to begin."
                        else -> "Choose a Primary profile above to begin."
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
        state.pendingApproval?.let { pending ->
            item(key = "approval") {
                ToolApprovalCard(pending, onResolve = onResolveApproval)
            }
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
                        modifier = Modifier.fillMaxWidth().testTag("composer-field"),
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
                    enabled = input.isNotBlank() && !state.routingPool.isEmpty,
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
    onReclaimStorage: () -> Unit,
    onCreateProfile: (LocalModelRecord) -> Unit,
    onUpdateProfile: (ModelProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onSaveEndpoint: (EndpointDraft) -> Unit,
    onRemoveEndpoint: (String) -> Unit,
    onAutoConfigure: (String) -> Unit,
    onTuneBatch: (String) -> Unit,
    onTuneDimension: (String, TuningDimension) -> Unit,
    /** The dimension being measured right now, so its row can say "Tuning…". */
    tuningDimension: TuningDimension?,
) {
    var expandedProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var addingProfile by rememberSaveable { mutableStateOf(false) }
    var profileCountAtOpen by rememberSaveable { mutableStateOf(0) }
    var showAdvancedTools by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.profiles.size, state.endpoints.size, addingProfile) {
        if (addingProfile && state.profiles.size + state.endpoints.size > profileCountAtOpen) {
            addingProfile = false
        }
    }
    if (addingProfile) {
        AddProfileScreen(
            state = state,
            onBack = { addingProfile = false },
            onImport = onImport,
            onSaveEndpoint = onSaveEndpoint,
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader("Profiles", modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        profileCountAtOpen = state.profiles.size + state.endpoints.size
                        addingProfile = true
                    },
                    enabled = !state.isImporting && !state.isGenerating,
                ) { Text("+", style = MaterialTheme.typography.headlineSmall) }
            }
        }
        if (state.profiles.isEmpty() && state.endpoints.isEmpty() && !state.isImporting) {
            item {
                GlassSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No profiles yet", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Tap + to add a GGUF from this phone or connect an OpenAI-compatible server.",
                        )
                    }
                }
            }
        }
        items(state.profiles, key = { it.id }) { profile ->
            val model = state.localModels.firstOrNull { it.id == profile.modelId }
            if (model != null) {
                val backend = RuntimeBackend.fromId(profile.backendId)
                    .takeIf { it in state.availableBackends } ?: RuntimeBackend.CPU
                ProfileCard(
                    profile = profile,
                    model = model,
                    expanded = expandedProfileId == profile.id,
                    role = when (profile.id) {
                        state.routingPool.primaryTargetId -> RoutingPoolSlot.PRIMARY
                        state.routingPool.powerTargetId -> RoutingPoolSlot.POWER
                        else -> null
                    },
                    loaded = state.activeProfileId == profile.id &&
                        state.loadedModelId == model.id.value && state.cpuValidated,
                    loading = state.isLoadingModel && state.activeProfileId == profile.id,
                    busy = state.isGenerating || state.isValidatingAccelerator ||
                        state.batchTuneProfileId != null || state.tuningProfileId != null,
                    tuning = state.batchTuneProfileId == profile.id || state.tuningProfileId == profile.id,
                    tuneStatus = state.status?.takeIf {
                        state.batchTuneProfileId == profile.id || state.tuningProfileId == profile.id
                    },
                    backend = backend,
                    availableBackends = state.availableBackends,
                    canDelete = state.profiles.count { it.modelId == profile.modelId } > 1,
                    onToggleExpanded = {
                        expandedProfileId = if (expandedProfileId == profile.id) null else profile.id
                    },
                    onUpdateProfile = onUpdateProfile,
                    onDeleteProfile = { onDeleteProfile(profile.id) },
                    onDuplicate = { onCreateProfile(model) },
                    onAutoConfigure = { onAutoConfigure(profile.id) },
                    onTuneBatch = { onTuneBatch(profile.id) },
                    onTuneDimension = { dimension -> onTuneDimension(profile.id, dimension) },
                    tuningDimension = tuningDimension,
                    staleMeasurement = profile.measuredFingerprint.isNotBlank() &&
                        profile.measuredFingerprint != state.measurementFingerprint,
                )
            }
        }
        items(state.endpoints, key = { "remote:${it.id}" }) { endpoint ->
            EndpointCard(endpoint, onRemoveEndpoint)
        }
        item {
            GlassSurface(
                Modifier
                    .fillMaxWidth()
                    .clickable { showAdvancedTools = !showAdvancedTools },
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Advanced tools", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Runtime details and storage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (showAdvancedTools) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (showAdvancedTools) {
            state.modelLoadDetail?.let { detail -> item { InfoCard("Active runtime", detail) } }
            if (state.modelStorageBytes > 0) {
                item {
                    GlassSurface(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Model storage", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Bram is using ${formatBytes(state.modelStorageBytes)} for model copies. " +
                                    "Copies left behind by an interrupted import can be removed safely.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = onReclaimStorage) { Text("Remove unreferenced copies") }
                        }
                    }
                }
            }
        }
        state.error?.let { error -> item { ErrorCard(error) } }
    }
}

@Composable
private fun AddProfileScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onSaveEndpoint: (EndpointDraft) -> Unit,
) {
    var addingServer by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("http://127.0.0.1:11434/v1") }
    var modelName by rememberSaveable { mutableStateOf("") }
    var context by rememberSaveable { mutableStateOf("32768") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var allowHttp by rememberSaveable { mutableStateOf(false) }
    var apiKind by rememberSaveable { mutableStateOf(RemoteApiKind.CHAT_COMPLETIONS) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DestinationHeader("Add profile", "Profiles", onBack)
        if (!addingServer) {
            GlassSurface(Modifier.fillMaxWidth().clickable(enabled = !state.isImporting, onClick = onImport)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Model on this phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Choose a GGUF file. Bram will inspect it, test this device, and create a ready-to-use profile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (state.isImporting) "Importing…" else "Choose GGUF  ›",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            GlassSurface(Modifier.fillMaxWidth().clickable { addingServer = true }) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Model on a server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Connect an OpenAI-compatible server and use its model as a profile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Connect server  ›", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Server details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(name, { name = it }, label = { Text("Profile name") }, modifier = Modifier.fillMaxWidth())
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
                    SectionLabel("API")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RemoteApiKind.entries.forEach { kind ->
                            FilterChip(
                                selected = kind == apiKind,
                                onClick = { apiKind = kind },
                                label = { Text(if (kind == RemoteApiKind.RESPONSES) "Responses" else "Chat Completions") },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allowHttp, { allowHttp = it })
                        Column {
                            Text("Allow insecure HTTP")
                            Text(
                                "Only for a server on a trusted local network.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(
                        onClick = {
                            onSaveEndpoint(
                                EndpointDraft(name, baseUrl, modelName, context.toIntOrNull() ?: 0, apiKey, allowHttp, apiKind),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Create profile") }
                    TextButton(onClick = { addingServer = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("Choose another source")
                    }
                }
            }
        }
        state.error?.let { ErrorCard(it) }
        Spacer(Modifier.height(24.dp))
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
                "Compares an accelerator against a CPU reference, prediction by prediction.",
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

/**
 * A batch configuration a profile can pin, in prompt/micro-batch tokens.
 *
 * 0/0 means llama.cpp's defaults (512/128), which is what Bram ran before the setting existed, so
 * it is the preset an untouched profile matches.
 */
private data class BatchPreset(
    val batch: Int,
    val ubatch: Int,
    val label: String,
) {
    fun matches(profile: ModelProfile): Boolean =
        profile.batchTokens == batch && profile.ubatchTokens == ubatch
}

private val batchPresets = listOf(
    BatchPreset(0, 0, "Default 512/128"),
    BatchPreset(256, 128, "256/128"),
    BatchPreset(512, 128, "512/128"),
    BatchPreset(512, 256, "512/256"),
    BatchPreset(1_024, 128, "1024/128"),
)

/**
 * One saved way of running a model.
 *
 * The profile is what a person picks, so it is the title; the file it runs is an attribute of it.
 * Collapsed it shows only what is needed to choose between profiles — its name, the model, and
 * whether it is loaded. Everything else is opt-in, because a phone with several profiles should not
 * open onto a wall of controls.
 */
@Composable
private fun ProfileCard(
    profile: ModelProfile,
    model: LocalModelRecord,
    expanded: Boolean,
    role: RoutingPoolSlot?,
    loaded: Boolean,
    loading: Boolean,
    busy: Boolean,
    tuning: Boolean,
    /** The live step while [tuning], shown inline so the measurement is not silent. */
    tuneStatus: String?,
    backend: RuntimeBackend,
    availableBackends: List<RuntimeBackend>,
    canDelete: Boolean,
    onToggleExpanded: () -> Unit,
    onUpdateProfile: (ModelProfile) -> Unit,
    onDeleteProfile: () -> Unit,
    onDuplicate: () -> Unit,
    onAutoConfigure: () -> Unit,
    onTuneBatch: () -> Unit,
    onTuneDimension: (TuningDimension) -> Unit,
    /** The dimension being measured right now, so its row can say "Tuning…". */
    tuningDimension: TuningDimension?,
    /** True when the profile's measurements were recorded under a different device/build. */
    staleMeasurement: Boolean = false,
) {
    // Settings the runtime reads at load time cannot change under a loaded model.
    val locked = loaded || loading || busy
    // Measuring reloads the model as part of its work and puts back whatever was loaded when it
    // finished, so a loaded model is no reason to refuse — only work already in flight is.
    val measuring = loading || busy
    var renaming by rememberSaveable(profile.id) { mutableStateOf(false) }
    var draftName by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var editingPrompt by rememberSaveable(profile.id) { mutableStateOf(false) }
    var draftPrompt by rememberSaveable(profile.id) { mutableStateOf(profile.systemPrompt) }
    var showAdvanced by rememberSaveable(profile.id) { mutableStateOf(false) }

    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (expanded && !renaming) {
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = { renaming = true },
                                modifier = Modifier.size(28.dp),
                            ) {
                                PencilIcon(
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                    Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                    Text(
                        buildString {
                            append(model.displayName).append(" · ").append(model.quantization)
                                .append(" · ").append(formatBytes(model.fileSizeBytes))
                            // A hybrid (SSM/mamba) arch re-decodes the whole thread every turn, so it
                            // cannot reuse the prompt cache. Tagged so a model picked for a long chat
                            // can be a transformer, which does.
                            if (isHybridArchitecture(model.architecture)) append(" · no prompt cache")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (role != null) {
                        Text(
                            role.label,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    } else if (loaded) {
                        Text(
                            "● In use",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    } else {
                        Text(
                            "${backend.label} · ${formatTokens(profile.contextTokens)}",
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

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (profile.measurements.isNotEmpty()) {
                        MeasurementPills(profile.measurements, Modifier.fillMaxWidth())
                    } else {
                        profile.autoConfiguredNote.takeIf(String::isNotBlank)?.let { note ->
                            Text(
                                note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // The shape of the tuned configuration at a glance: which dimensions were
                    // measured and what won, so the collapsed card reads like the batch pills do.
                    TuningSummary(profile, Modifier.fillMaxWidth())
                    if (staleMeasurement) {
                        Text(
                            "These measurements were taken on a different device or build. " +
                                "Run Auto-configure to re-measure on this one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                HorizontalDivider()

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
                                // A nameless profile is unpickable in the list, so an empty field
                                // keeps the old name rather than producing a blank row.
                                draftName.trim().takeIf(String::isNotEmpty)?.let { name ->
                                    onUpdateProfile(profile.copy(name = name))
                                }
                                renaming = false
                            },
                        ) { Text("Save name") }
                        TextButton(onClick = { renaming = false }) { Text("Cancel") }
                    }
                }

                // The pills above are the argument for this button: re-measuring is how the choice
                // of backend changes, and the manual controls sit behind an expander so the card
                // stays short until someone actually wants to turn a dial.
                Button(onClick = onAutoConfigure, enabled = !measuring, modifier = Modifier.fillMaxWidth()) {
                    Text("Auto-configure")
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showAdvanced = !showAdvanced }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("Advanced settings", Modifier.weight(1f))
                    Text(
                        if (showAdvanced) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showAdvanced) {
                    SectionLabel("Backend")
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        availableBackends.forEach { candidate ->
                            FilterChip(
                                selected = candidate == backend,
                                onClick = {
                                    onUpdateProfile(
                                        profile.copy(
                                            backendId = if (candidate == RuntimeBackend.CPU) "" else candidate.name,
                                        ),
                                    )
                                },
                                enabled = !locked,
                                label = { Text(candidate.label) },
                            )
                        }
                    }

                    SectionLabel("Context")
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        contextOptions(model).forEach { tokens ->
                            FilterChip(
                                selected = tokens == profile.contextTokens,
                                onClick = { onUpdateProfile(profile.copy(contextTokens = tokens)) },
                                enabled = !locked,
                                label = { Text(formatTokens(tokens)) },
                            )
                        }
                    }

                    SectionLabel("Sampling")
                    SamplerControls(
                        profile = profile,
                        enabled = !busy,
                        onUpdateProfile = onUpdateProfile,
                    )

                    // These two are context parameters rather than sampling ones: they change how
                    // the load builds the attention cache, so the accelerator comparison measures
                    // them together with the backend choice rather than in isolation.
                    SectionLabel("Flash attention")
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FlashAttentionMode.entries.forEach { mode ->
                            FilterChip(
                                selected = mode == profile.flashAttention,
                                onClick = {
                                    // A quantized V cache needs flash attention, so turning it off
                                    // also returns the cache to F16 rather than saving an unusable
                                    // combination.
                                    val kv = if (mode == FlashAttentionMode.OFF) KvCacheType.F16 else profile.kvCacheType
                                    onUpdateProfile(
                                        profile.copy(flashAttention = mode, kvCacheType = kv),
                                    )
                                },
                                enabled = !busy,
                                label = { Text(mode.label) },
                            )
                        }
                    }

                    SectionLabel("Attention memory")
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        KvCacheType.entries.forEach { type ->
                            // llama.cpp refuses a quantized V cache without flash attention on,
                            // so the combination is simply not offered.
                            val allowed = type == KvCacheType.F16 || profile.flashAttention != FlashAttentionMode.OFF
                            FilterChip(
                                selected = type == profile.kvCacheType,
                                onClick = { onUpdateProfile(profile.copy(kvCacheType = type)) },
                                enabled = !busy && allowed,
                                label = { Text(type.label) },
                            )
                        }
                    }
                    Text(
                        profile.kvCacheType.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Reasoning", Modifier.weight(1f))
                        Checkbox(
                            checked = profile.thinkingEnabled,
                            onCheckedChange = { onUpdateProfile(profile.copy(thinkingEnabled = it)) },
                            enabled = !busy,
                        )
                    }

                    // The batch is a context parameter like flash attention: it changes how the
                    // prompt is evaluated, so it belongs with the load-time settings rather than
                    // the sampling ones. It is also the biggest lever on time-to-first-token, so
                    // the measured option sits right beside the manual presets instead of hiding
                    // behind the auto-configure flow.
                    SectionLabel("Batch")
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        batchPresets.forEach { preset ->
                            FilterChip(
                                selected = preset.matches(profile),
                                onClick = {
                                    onUpdateProfile(
                                        profile.copy(
                                            batchTokens = preset.batch,
                                            ubatchTokens = preset.ubatch,
                                        ),
                                    )
                                },
                                enabled = !locked,
                                label = { Text(preset.label) },
                            )
                        }
                    }
                    Button(
                        onClick = onTuneBatch,
                        enabled = !measuring,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (tuning) "Tuning…" else "Tune batch")
                    }
                    tuneStatus?.let { status ->
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    profile.batchTuneNote.takeIf(String::isNotBlank)?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // The decode-shaped dimensions follow the batch pattern: a measured choice is a
                    // note beside a Tune button, and Default is always one tap away. Each one only
                    // matters on the hardware that exposes it, so the Hexagon row appears only when
                    // this profile loads onto the NPU.
                    SectionLabel("Decode tuning")
                    TuningDimensionRow(
                        label = "Threads",
                        isDefault = profile.threads == 0,
                        chosen = profile.threads.takeIf { it > 0 }?.let { "$it threads" },
                        measuring = tuningDimension == TuningDimension.THREADS,
                        note = profile.tuning.firstOrNull { it.dimension == TuningDimension.THREADS }?.note,
                        enabled = !busy,
                        onDefault = { onUpdateProfile(profile.copy(threads = 0)) },
                        onTune = { onTuneDimension(TuningDimension.THREADS) },
                    )
                    TuningDimensionRow(
                        label = "CPU mask",
                        isDefault = profile.cpuMask.isEmpty(),
                        chosen = profile.cpuMask.takeIf(String::isNotEmpty)?.let { "0x$it" },
                        measuring = tuningDimension == TuningDimension.CPU_MASK,
                        note = profile.tuning.firstOrNull { it.dimension == TuningDimension.CPU_MASK }?.note,
                        enabled = !busy,
                        onDefault = {
                            onUpdateProfile(profile.copy(cpuMask = "", cpuStrict = false))
                        },
                        onTune = { onTuneDimension(TuningDimension.CPU_MASK) },
                    )
                    TuningDimensionRow(
                        label = "Poll",
                        isDefault = profile.poll < 0,
                        chosen = profile.poll.takeIf { it >= 0 }?.let { "$it" },
                        measuring = tuningDimension == TuningDimension.POLL,
                        note = profile.tuning.firstOrNull { it.dimension == TuningDimension.POLL }?.note,
                        enabled = !busy,
                        onDefault = { onUpdateProfile(profile.copy(poll = -1)) },
                        onTune = { onTuneDimension(TuningDimension.POLL) },
                    )
                    SectionLabel("Load mode")
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LoadMode.entries.forEach { mode ->
                            FilterChip(
                                selected = mode == profile.loadMode,
                                onClick = { onUpdateProfile(profile.copy(loadMode = mode)) },
                                enabled = !locked,
                                label = { Text(mode.label) },
                            )
                        }
                        TextButton(
                            onClick = { onTuneDimension(TuningDimension.LOAD_MODE) },
                            enabled = !measuring,
                        ) {
                            Text(
                                if (tuningDimension == TuningDimension.LOAD_MODE) "Tuning…" else "Tune",
                            )
                        }
                    }
                    profile.tuning.firstOrNull { it.dimension == TuningDimension.LOAD_MODE }?.note
                        ?.takeIf(String::isNotBlank)?.let { note ->
                            Text(
                                note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    if (backend == RuntimeBackend.HEXAGON) {
                        TuningDimensionRow(
                            label = "Hexagon",
                            isDefault = profile.hexFlags.isDefault,
                            chosen = if (profile.hexFlags.isDefault) null else "HMX + host buffers",
                            measuring = tuningDimension == TuningDimension.HEX_FLAGS,
                            note = profile.tuning.firstOrNull { it.dimension == TuningDimension.HEX_FLAGS }?.note,
                            enabled = !busy,
                            onDefault = { onUpdateProfile(profile.copy(hexFlags = HexFlags())) },
                            onTune = { onTuneDimension(TuningDimension.HEX_FLAGS) },
                        )
                    }
                }

                HorizontalDivider()

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { editingPrompt = !editingPrompt }) {
                        Text(if (profile.systemPrompt.isBlank()) "Add instructions" else "Instructions")
                    }
                    // The only way left to get a second profile for one file, now that the
                    // separate creation row is gone.
                    TextButton(onClick = onDuplicate, enabled = !busy) { Text("Duplicate") }
                    if (canDelete) {
                        TextButton(onClick = onDeleteProfile, enabled = !locked) { Text("Delete") }
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
                        "Added to Bram's own instructions, not replacing them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onUpdateProfile(profile.copy(systemPrompt = draftPrompt.trim()))
                                editingPrompt = false
                            },
                        ) { Text("Save instructions") }
                        TextButton(onClick = {
                            draftPrompt = profile.systemPrompt
                            editingPrompt = false
                        }) { Text("Cancel") }
                    }
                }

                Text(
                    "${model.layerCount.takeIf { it > 0 } ?: "?"} layers · trained context " +
                        "${formatTokens(model.trainedContextTokens)} · SHA ${model.sha256.take(12)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
            }
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
    Text(
        if (sampler.isGreedy) {
            "Temperature 0 — same answer every time"
        } else {
            "Temperature ${"%.2f".format(sampler.temperature)}"
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
        "Repetition penalty ${"%.2f".format(sampler.repeatPenalty)}",
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
        "Top-p ${"%.2f".format(sampler.topP)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = sampler.topP,
        onValueChange = { onUpdateProfile(profile.copy(sampler = sampler.copy(topP = it))) },
        valueRange = 0.05f..1f,
        enabled = enabled,
    )

    Text(
        if (sampler.topK <= 0) "Top-k off" else "Top-k ${sampler.topK}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = sampler.topK.toFloat(),
        onValueChange = { onUpdateProfile(profile.copy(sampler = sampler.copy(topK = it.toInt()))) },
        valueRange = 0f..100f,
        enabled = enabled,
    )
}

/**
 * What the processors scored, side by side.
 *
 * A row of pills rather than a sentence about the winner: "NPU 1.4x" beside "Vulkan failed" says
 * what this device can do in one glance, where prose about the winner hides everything it rejected.
 * A failure is red because it is not a slower option, it is a wrong one. The pills wrap rather than
 * scroll, because a result someone measured and had to read is a result, not a feed.
 */
@Composable
private fun MeasurementPills(measurements: List<BackendMeasurement>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        measurements.forEach { measurement ->
            val failed = !measurement.agrees && !measurement.isReference
            val container = when {
                failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
                measurement.isReference -> MaterialTheme.colorScheme.surfaceContainerHighest
                else -> MaterialTheme.colorScheme.primaryContainer
            }
            val ink = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            Row(
                Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(container)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    measurement.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = ink,
                )
                Text(
                    if (failed) "FAIL" else "%.2fx".format(measurement.speedup),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ink,
                )
            }
        }
    }
}

/**
 * Auto-configure while it runs.
 *
 * An overlay in the app tree rather than a Dialog window, because this takes minutes and replaces
 * every setting underneath it — a person needs to see that something is happening to it. Each
 * processor gets a row that moves from Waiting to Measuring to its score, so the arrival of every
 * result is visible rather than a number appearing at the bottom.
 *
 * It is drawn here rather than in its own window for the same reason the panels and drawer are: a
 * separate window has nothing of the app behind it to sample, so it can never be frosted. Inside
 * the tree it blurs the recorded backdrop like any other panel.
 */
@Composable
private fun BoxScope.AutoConfigureOverlay(progress: AutoConfigureProgress, onDismiss: () -> Unit) {
    Box(Modifier.matchParentSize().zIndex(10f)) {
        Scrim(onDismiss = { if (progress.finished) onDismiss() })
        GlassSurface(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(Glass.cornerLarge),
            alpha = Glass.chromeAlpha,
            tint = Glass.panelTint,
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (progress.finished) "Configured" else "Auto-configure",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    progress.modelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()

                // The CPU is the reference the others are measured against, so it is a row like
                // every candidate rather than a footnote to them.
                AutoConfigureRow(
                    label = "CPU",
                    status = "reference",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                progress.candidates.forEachIndexed { index, label ->
                    val result = progress.results.getOrNull(index + 1)
                    // measuringIndex is the single source of truth for "this row is the one running
                    // now"; matching against `current` would flicker, because `current` carries the
                    // measurement callback's prose mid-run.
                    val measuring = !progress.finished && progress.measuringIndex == index
                    val (status, color) = when {
                        result != null && result.agrees ->
                            "%.2fx".format(result.speedup) to MaterialTheme.colorScheme.primary
                        result != null -> "FAIL" to MaterialTheme.colorScheme.error
                        measuring -> "Measuring…" to MaterialTheme.colorScheme.primary
                        else -> "Waiting" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    AutoConfigureRow(label = label, status = status, color = color)
                }

                if (!progress.finished) {
                    // Indeterminate and animated: the work is discrete (one backend at a time, then
                    // the batch tune), each step lasting seconds, so a determinate bar that stalls
                    // then jumps reads as broken. The rows carry the per-candidate progress; the bar
                    // just says "still working."
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    // The live activity — "Replaying the reference on Adreno…", then "Tuning prompt
                    // batch…" — including the batch phase, which previously had no visible indicator.
                    Text(
                        progress.current,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

/** One processor in the auto-configure overlay: name on the left, live status on the right. */
@Composable
private fun AutoConfigureRow(label: String, status: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            status,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/**
 * One tuning dimension on the profile card: label, the measured choice when there is one, and a
 * Tune button that runs the same teacher-forced sweep the batch tuner does. Default restores the
 * device default, which is what an untouched profile runs.
 */
@Composable
private fun TuningDimensionRow(
    label: String,
    isDefault: Boolean,
    chosen: String?,
    measuring: Boolean,
    note: String?,
    enabled: Boolean,
    onDefault: () -> Unit,
    onTune: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            chosen?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            TextButton(onClick = onDefault, enabled = enabled && !isDefault) {
                Text("Default", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = onTune, enabled = enabled) {
                Text(
                    if (measuring) "Tuning…" else "Tune",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        note?.takeIf(String::isNotBlank)?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The measured tuning configuration in one line, for the collapsed profile card. */
@Composable
private fun TuningSummary(profile: ModelProfile, modifier: Modifier = Modifier) {
    val bits = buildList {
        profile.tuning.forEach { note ->
            if (note.chosen != "Default") add("${note.dimension.label} ${note.chosen}")
        }
        profile.batchTuneNote.takeIf(String::isNotBlank)?.let { note ->
            add(note.substringAfter("Tuned batch ").substringBefore(" on ").let { "batch $it" })
        }
    }
    if (bits.isNotEmpty()) {
        Text(
            bits.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

@Composable
private fun RoutingSummaryScreen(
    state: AppUiState,
    onAssign: (RoutingPoolSlot, String?) -> Unit,
    onManageProfiles: () -> Unit,
    onConversationDetails: () -> Unit,
    onSetRoutingMode: (RoutingMode) -> Unit,
    onSetDefaultPrivacyClass: (PrivacyClass) -> Unit,
) {
    var showPolicy by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Model selection", "Choose the profiles Bram can use")

        RoutingSlotSection(
            title = RoutingPoolSlot.PRIMARY.label,
            subtitle = "Preferred for everyday work",
            selectedId = state.routingPool.primaryTargetId,
            choices = buildList {
                addAll(state.profiles.map { it.id to it.name })
                addAll(state.endpoints.map { remoteRuntimeId(it.id) to it.displayName })
            }.filter { it.first != state.routingPool.powerTargetId },
            allowEmpty = false,
            onSelect = { onAssign(RoutingPoolSlot.PRIMARY, it) },
        )
        RoutingSlotSection(
            title = RoutingPoolSlot.POWER.label,
            subtitle = "Optional escalation for demanding work",
            selectedId = state.routingPool.powerTargetId,
            choices = buildList {
                addAll(state.profiles.map { it.id to it.name })
                addAll(state.endpoints.map { remoteRuntimeId(it.id) to it.displayName })
            }.filter { it.first != state.routingPool.primaryTargetId },
            allowEmpty = true,
            emptyText = "Add another profile to enable escalation",
            onSelect = { onAssign(RoutingPoolSlot.POWER, it) },
        )
        GlassSurface(
            Modifier.fillMaxWidth().clickable { showPolicy = !showPolicy },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Preferences", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${routingModeUiLabel(state.routingMode)} · new chats ${state.defaultPrivacyClass.label.lowercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (showPolicy) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(
                    visible = showPolicy,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    SectionLabel("Available profiles")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RoutingMode.entries.forEach { mode ->
                            FilterChip(
                                selected = mode == state.routingMode,
                                onClick = { onSetRoutingMode(mode) },
                                label = { Text(routingModeUiLabel(mode)) },
                            )
                        }
                    }
                    SectionLabel("Privacy for new conversations")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PrivacyClass.entries.forEach { privacyClass ->
                            FilterChip(
                                selected = privacyClass == state.defaultPrivacyClass,
                                onClick = { onSetDefaultPrivacyClass(privacyClass) },
                                label = { Text(privacyClassUiLabel(privacyClass)) },
                            )
                        }
                    }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onManageProfiles) { Text("Manage profiles") }
            TextButton(onClick = onConversationDetails) { Text("Conversation details") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun routingModeUiLabel(mode: RoutingMode): String = when (mode) {
    RoutingMode.AUTO -> "Local and server"
    RoutingMode.LOCAL_ONLY -> "Local only"
    RoutingMode.REMOTE_ONLY -> "Server only"
}

@Composable
private fun RoutingSlotSection(
    title: String,
    subtitle: String,
    selectedId: String?,
    choices: List<Pair<String, String>>,
    allowEmpty: Boolean,
    emptyText: String = if (allowEmpty) "Not configured" else "Add a local profile to continue",
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(title, subtitle)
        if (choices.isEmpty()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (allowEmpty) {
                    FilterChip(
                        selected = selectedId == null,
                        onClick = { onSelect(null) },
                        label = { Text("None") },
                    )
                }
                choices.forEach { (id, label) ->
                    FilterChip(
                        selected = selectedId == id,
                        onClick = { onSelect(id) },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionScreen(
    state: AppUiState,
    onSetMode: (PermissionMode) -> Unit,
    onSetPrivacyClass: (PrivacyClass) -> Unit,
) {
    var showStats by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Conversation details")

        // The mode is the one thing that changes how the conversation behaves rather than what it
        // shows, so it sits at the top. The helper text says what the current selection does, since
        // three one-word labels leave the difference between them to guesswork.
        SectionLabel("Tool approvals")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PermissionMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == state.permissionMode,
                    onClick = { onSetMode(mode) },
                    label = { Text(permissionModeUiLabel(mode)) },
                )
            }
        }
        Text(
            when (state.permissionMode) {
                PermissionMode.AUTO -> "Ask before tools make changes."
                PermissionMode.MANUAL -> "Ask before every tool."
                // Not quite "never": a call read out of the reply text is still asked about once
                // the chat contains a fetched page, which is the one case the mode cannot honestly
                // waive. Said here so the exception is not a surprise when it happens.
                PermissionMode.BYPASS -> "Never ask, except a call read from text after a web fetch."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Privacy is where routing meets trust: which runtimes this conversation's content may go
        // to. It lives beside the permission mode because both are per-conversation decisions.
        SectionLabel("Privacy")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PrivacyClass.entries.forEach { privacyClass ->
                FilterChip(
                    selected = privacyClass == state.privacyClass,
                    onClick = { onSetPrivacyClass(privacyClass) },
                    label = { Text(privacyClassUiLabel(privacyClass)) },
                )
            }
        }
        Text(
            when (state.privacyClass) {
                PrivacyClass.STANDARD -> "Use any assigned profile."
                PrivacyClass.PRIVATE_REMOTE_ALLOWED -> "Prefer a model on this phone."
                PrivacyClass.LOCAL_ONLY -> "Keep this conversation on this phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val userCount = state.messages.count { it.role == MessageRole.USER }
        val assistantCount = state.messages.count { it.role == MessageRole.ASSISTANT }
        val contextWindow = state.selectedLocalModel?.preferredContextTokens
            ?: state.selectedEndpoint?.contextWindowTokens
        GlassSurface(Modifier.fillMaxWidth().clickable { showStats = !showStats }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Usage", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${state.messages.size} messages · ${formatTokenCount(state.sessionOutputTokens)} generated",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(if (showStats) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AnimatedVisibility(
                    visible = showStats,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        StatRow("Messages", "${state.messages.size}" + if (state.messages.isNotEmpty()) " · $userCount you, $assistantCount Bram" else "")
                        StatRow("Tokens read", formatTokenCount(state.sessionInputTokens))
                        StatRow(
                            "Context",
                            when {
                                state.lastContextTokens != null && contextWindow != null ->
                                    "${formatTokens(state.lastContextTokens)} / ${formatTokens(contextWindow)}"
                                state.lastContextTokens != null -> formatTokens(state.lastContextTokens)
                                contextWindow != null -> "${formatTokens(contextWindow)} window"
                                else -> "Not used yet"
                            },
                        )
                        state.lastMetrics?.let { metrics ->
                            StatRow(
                                "Last turn",
                                "${formatTokens(metrics.promptTokens)} in · ${formatTokens(metrics.outputTokens)} out" +
                                    metrics.decodeTokensPerSecond?.let { " · ${formatRate(it)}" }.orEmpty(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun permissionModeUiLabel(mode: PermissionMode): String = when (mode) {
    PermissionMode.AUTO -> "Ask when needed"
    PermissionMode.MANUAL -> "Always ask"
    PermissionMode.BYPASS -> "Don't ask"
}

private fun privacyClassUiLabel(privacyClass: PrivacyClass): String = when (privacyClass) {
    PrivacyClass.STANDARD -> "Any profile"
    PrivacyClass.PRIVATE_REMOTE_ALLOWED -> "Prefer phone"
    PrivacyClass.LOCAL_ONLY -> "Phone only"
}

/** A label and a value on one line, the shape every stat row takes. */
@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One line of the run journal: when, how it ended, and what it did and cost. */
@Composable
private fun RunJournalRow(entry: RunJournalEntry) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (entry.status) {
                    RunStatus.RUNNING -> "Running"
                    RunStatus.SUCCEEDED -> "Succeeded"
                    RunStatus.FAILED -> "Failed"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (entry.status) {
                    RunStatus.FAILED -> MaterialTheme.colorScheme.error
                    RunStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
                    RunStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
                },
            )
            Text(
                java.text.SimpleDateFormat(
                    "MMM d, HH:mm",
                    LocalLocale.current.platformLocale,
                ).format(java.util.Date(entry.startedAtEpochMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            buildString {
                if (entry.toolTurns > 0) append("${entry.toolTurns} tool turns")
                val tokens = listOf(entry.inputTokens?.let { "in: $it" }, entry.outputTokens?.let { "out: $it" })
                    .filterNotNull()
                    .joinToString(" · ")
                if (tokens.isNotEmpty()) {
                    if (entry.toolTurns > 0) append(" · ")
                    append(tokens)
                }
                if (entry.toolTurns == 0 && tokens.isEmpty()) append("—")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        entry.error?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * A count for the stats card. Unlike [formatTokens], zero is shown as 0 rather than "unknown",
 * because a fresh session genuinely has generated nothing — "unknown" would read as a missing
 * measurement rather than an honest empty total.
 */
private fun formatTokenCount(tokens: Int): String =
    if (tokens <= 0) "0" else formatTokens(tokens)

@Composable
private fun SettingsScreen(
    state: AppUiState,
    onOpenPanel: (AppPanel) -> Unit,
    onSetCompletionAlerts: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Settings", "App behavior and system destinations")
        SectionHeader("Notifications")
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Completion alert", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Show the completed reply when a turn finishes while Bram is in the background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.completionAlertsEnabled,
                        onCheckedChange = onSetCompletionAlerts,
                    )
                }
                val permissionGranted = ContextCompat.checkSelfPermission(
                    LocalContext.current,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                if (state.completionAlertsEnabled && !permissionGranted) {
                    Text(
                        "Notification permission is off, so completion alerts will not show. " +
                            "Turn it on for Bram in the system settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        SectionHeader("Destinations")
        DestinationCard("Routing and privacy", "Profile roles and defaults", onClick = { onOpenPanel(AppPanel.ROUTING) })
        DestinationCard("Profiles and models", "Local GGUF and server profiles", onClick = { onOpenPanel(AppPanel.MODELS) })
        DestinationCard("Capabilities", "Providers, tools, skills, automations, and memories", onClick = { onOpenPanel(AppPanel.CAPABILITIES) })
        DestinationCard("System", "Device diagnostics and recent runs", onClick = { onOpenPanel(AppPanel.SYSTEM) })
        state.error?.let { ErrorCard(it) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CapabilitiesScreen(state: AppUiState, onOpenPanel: (AppPanel) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Capabilities", "What Bram can use beyond the selected model")
        DestinationCard(
            "Tools",
            "Built-ins, Termux, MCP servers, and permissions",
            status = if (state.mcpServers.isEmpty()) "Built-in" else "${state.mcpServers.size} MCP",
            onClick = { onOpenPanel(AppPanel.TOOLS) },
        )
        DestinationCard(
            "Skills",
            "Reusable agent procedures",
            status = state.skills.size.toString(),
            onClick = { onOpenPanel(AppPanel.SKILLS) },
        )
        DestinationCard(
            "Automations",
            "Scheduled agent tasks",
            status = state.automations.size.toString(),
            onClick = { onOpenPanel(AppPanel.AUTOMATIONS) },
        )
        DestinationCard(
            "Memories",
            "Facts and instructions remembered from conversations",
            status = state.memories.size.toString(),
            onClick = { onOpenPanel(AppPanel.MEMORIES) },
        )
    }
}

@Composable
private fun ProvidersScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSaveEndpoint: (EndpointDraft) -> Unit,
    onRemoveEndpoint: (String) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("http://127.0.0.1:11434/v1") }
    var modelName by rememberSaveable { mutableStateOf("") }
    var context by rememberSaveable { mutableStateOf("32768") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var allowHttp by rememberSaveable { mutableStateOf(false) }
    var apiKind by rememberSaveable { mutableStateOf(RemoteApiKind.CHAT_COMPLETIONS) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DestinationHeader("Remote providers", "Capabilities", onBack)
        Text(
            "Optional OpenAI-compatible capacity. Local GGUF chat does not require a provider.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.endpoints.isEmpty()) {
            EmptyDestination("No providers yet", "Add one when you want to use a model hosted on a server.")
        } else {
            state.endpoints.forEach { EndpointCard(it, onRemoveEndpoint) }
        }
        if (!adding) {
            Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add provider") }
        } else {
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Add provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                    SectionLabel("API")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RemoteApiKind.entries.forEach { kind ->
                            FilterChip(
                                selected = kind == apiKind,
                                onClick = { apiKind = kind },
                                label = { Text(if (kind == RemoteApiKind.RESPONSES) "Responses" else "Chat Completions") },
                            )
                        }
                    }
                    Text(
                        if (apiKind == RemoteApiKind.CHAT_COMPLETIONS) {
                            "Works with Ollama, LM Studio, vLLM, llama.cpp server, and most compatible hosts."
                        } else {
                            "For hosts exposing /responses rather than /chat/completions."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allowHttp, { allowHttp = it })
                        Column {
                            Text("Allow insecure HTTP")
                            Text(
                                "Only for a trusted local network.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(
                        onClick = {
                            onSaveEndpoint(
                                EndpointDraft(name, baseUrl, modelName, context.toIntOrNull() ?: 0, apiKey, allowHttp, apiKind),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save provider") }
                    TextButton(onClick = { adding = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
        }
        state.error?.let { ErrorCard(it) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToolsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSaveMcpServer: (McpServerDraft) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onRefreshMcpServers: () -> Unit,
    onWithdrawToolPermission: (String) -> Unit,
) {
    var addingMcp by rememberSaveable { mutableStateOf(false) }
    var mcpName by rememberSaveable { mutableStateOf("") }
    var mcpBaseUrl by rememberSaveable { mutableStateOf("http://") }
    var mcpToken by rememberSaveable { mutableStateOf("") }
    var mcpAllowHttp by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DestinationHeader("Tools", "Capabilities", onBack)
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Built-in tools", fontWeight = FontWeight.SemiBold)
                Text(
                    "Memory, web access, notifications, and device actions are available when a task needs them. Side effects still require approval.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Termux command bridge", fontWeight = FontWeight.SemiBold)
                    Text("Optional", color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "Expert coding and build workflows can use the approval-gated termux_exec tool when Termux:API and Android command access are installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("MCP servers", "External tool collections", Modifier.weight(1f))
            if (state.mcpRefreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else TextButton(onClick = onRefreshMcpServers) { Text("Refresh") }
        }
        if (state.mcpServers.isEmpty()) {
            EmptyDestination("No MCP servers", "Connect one when Bram needs tools hosted elsewhere.")
        } else {
            state.mcpServers.forEach { McpServerCard(it, onRemoveMcpServer) }
        }
        if (!addingMcp) {
            OutlinedButton(onClick = { addingMcp = true }, modifier = Modifier.fillMaxWidth()) { Text("Connect MCP server") }
        } else {
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Connect MCP server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(mcpName, { mcpName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        mcpBaseUrl,
                        { mcpBaseUrl = it },
                        label = { Text("Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        mcpToken,
                        { mcpToken = it },
                        label = { Text("Bearer token (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(mcpAllowHttp, { mcpAllowHttp = it })
                        Text("Allow HTTP on a trusted local network")
                    }
                    Button(
                        onClick = { onSaveMcpServer(McpServerDraft(mcpName, mcpBaseUrl, mcpToken, mcpAllowHttp)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Connect and list tools") }
                    TextButton(onClick = { addingMcp = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
        }
        if (state.alwaysAllowedTools.isNotEmpty()) {
            SectionHeader("Persistent permissions", "Tools previously allowed for a matching target")
            state.alwaysAllowedTools.forEach { scope ->
                GlassSurface(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(scope, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        TextButton(onClick = { onWithdrawToolPermission(scope) }) { Text("Withdraw") }
                    }
                }
            }
        }
        state.error?.let { ErrorCard(it) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SkillsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onImportSkill: () -> Unit,
    onSaveSkill: (String) -> Unit,
    onActivateSkillDraft: (String) -> Unit,
    onRollbackSkill: (String) -> Unit,
    onRemoveSkill: (String) -> Unit,
) {
    // The editor writes the same SKILL.md document the file importer reads, so authoring in-app
    // and importing a file land in the same place: a new skill is active, a new version of an
    // existing one is a draft the user activates.
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var version by rememberSaveable { mutableStateOf("1.0.0") }
    var description by rememberSaveable { mutableStateOf("") }
    var instructions by rememberSaveable { mutableStateOf("") }

    fun openEditorForNew() {
        name = ""; version = "1.0.0"; description = ""; instructions = ""
        editorOpen = true
    }
    fun openEditorForEdit(pkg: SkillPackage) {
        val source = pkg.versions.firstOrNull { it.version == pkg.activeVersion }
            ?: pkg.versions.firstOrNull()
        name = pkg.name
        version = source?.version ?: "1.0.0"
        description = source?.description.orEmpty()
        instructions = source?.instructions.orEmpty()
        editorOpen = true
    }
    fun save() {
        val document = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("version: $version")
            appendLine("description: $description")
            appendLine("---")
            append(instructions)
        }
        onSaveSkill(document)
        editorOpen = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DestinationHeader("Skills", "Capabilities", onBack)
        Text(
            "Versioned procedures Bram follows when their description matches a task. Skills are instructions, not executable code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.skillStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (state.skills.isEmpty()) EmptyDestination("No skills imported", "Author one, or import a file, when you want a repeatable specialist workflow.")
        else state.skills.forEach { SkillCard(it, ::openEditorForEdit, onActivateSkillDraft, onRollbackSkill, onRemoveSkill) }
        if (!editorOpen) {
            Button(onClick = ::openEditorForNew, modifier = Modifier.fillMaxWidth()) { Text("New skill") }
            OutlinedButton(onClick = onImportSkill, modifier = Modifier.fillMaxWidth()) { Text("Import skill file") }
        } else {
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Skill", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, supportingText = { Text("1–48 chars, no colons") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(version, { version = it }, label = { Text("Version") }, supportingText = { Text("three numbers, like 1.2.0") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(description, { description = it }, label = { Text("Description") }, supportingText = { Text("when Bram should follow this") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        instructions,
                        { instructions = it },
                        label = { Text("Instructions") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                    )
                    Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Save skill") }
                    TextButton(onClick = { editorOpen = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
        }
        state.error?.let { ErrorCard(it) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AutomationsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSaveAutomation: (String, String, String) -> Unit,
    onRemoveAutomation: (String) -> Unit,
    onSetAutomationEnabled: (String, Boolean) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var cron by rememberSaveable { mutableStateOf("0 9 * * *") }
    var prompt by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DestinationHeader("Automations", "Capabilities", onBack)
        Text(
            "Scheduled prompts enter the task queue and use the same routing and approval rules as other work.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.automationStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (state.automations.isEmpty()) EmptyDestination("No automations", "Create one when Bram should work on a schedule.")
        else state.automations.forEach { AutomationCard(it, onSetAutomationEnabled, onRemoveAutomation) }
        if (!adding) {
            Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("New automation") }
        } else {
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("New automation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        cron,
                        { cron = it },
                        label = { Text("Schedule") },
                        supportingText = { Text("minute hour day month weekday — for example, 0 9 * * 1-5") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(prompt, { prompt = it }, label = { Text("Prompt") }, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = { onSaveAutomation(name, cron, prompt) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save automation") }
                    TextButton(onClick = { adding = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
        }
        state.error?.let { ErrorCard(it) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MemoriesScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onRemoveMemory: (String) -> Unit,
    onSetEmbeddingModel: (String) -> Unit,
    onClearEmbeddingModel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DestinationHeader("Memories", "Capabilities", onBack)
        Text(
            "Facts and standing instructions Bram retained from past conversations. Remove anything wrong or stale.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Semantic recall", "Match memories by meaning, not just shared words")
                state.embeddingModelName?.let { name ->
                    Text(
                        "Embedding model: $name",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onClearEmbeddingModel) { Text("Stop using embeddings") }
                } ?: run {
                    Text(
                        "An embedding model lets recall find a memory like 'dark mode' for the query " +
                            "'appearance settings'. Import a small embedding GGUF (bge-small-en, " +
                            "all-MiniLM-L6-v2, or similar) and pick it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.localModels.isEmpty()) {
                        Text("No models imported yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        state.localModels.forEach { model ->
                            TextButton(onClick = { onSetEmbeddingModel(model.id.value) }) {
                                Text(model.displayName)
                            }
                        }
                    }
                }
            }
        }
        if (state.memories.isEmpty()) EmptyDestination("No memories yet", "They are gathered automatically as you chat.")
        else state.memories.forEach { MemoryCard(it, onRemoveMemory) }
        state.error?.let { ErrorCard(it) }
    }
}

@Composable
private fun SystemScreen(state: AppUiState, onBack: () -> Unit, onRefreshDiagnostics: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DestinationHeader("System", "Settings", onBack)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Device", "Hardware and memory summary", Modifier.weight(1f))
            TextButton(onClick = onRefreshDiagnostics) { Text("Refresh") }
        }
        state.deviceProfile?.let { profile ->
            DeviceSummaryCard(profile)
        } ?: CircularProgressIndicator()
        SectionHeader("Agent foundation")
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadinessRow("Context budgeting", "Working")
                ReadinessRow("Remote tool loop", "Working")
                ReadinessRow(
                    "Local isolated inference",
                    if (state.cpuValidated) "Running on ${state.loadedBackend?.label ?: "this device"}" else "Prepared when needed",
                )
                ReadinessRow("Conversation memory", "Working")
                ReadinessRow("Skills and automation", "Working")
            }
        }
        SectionHeader("Recent runs", "Agent activity, outcomes, and measured usage")
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.recentRuns.isEmpty()) {
                    Text("No runs recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.recentRuns.forEach { RunJournalRow(it) }
            }
        }
        state.error?.let { ErrorCard(it) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DestinationHeader(title: String, parent: String, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹ $parent") }
        SectionHeader(title)
    }
}

@Composable
private fun DestinationCard(
    title: String,
    subtitle: String,
    status: String? = null,
    onClick: () -> Unit,
) {
    GlassSurface(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status != null) {
                Text(status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyDestination(title: String, subtitle: String) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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

        // Shown with the actions, on the same tap: a timestamp under every message is furniture in
        // a long transcript, but "when did I ask this" is a fair question to have on demand.
        if (showActions && !editing) {
            Text(
                formatSentAt(message.createdAtEpochMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp),
            )
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
    ActivityList(message.id.value, message.activity)
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
 * One question and three answers.
 *
 * The long form — description, required permissions, a warning, the raw arguments, and a sentence
 * about what "always" would grant — was more reading than a decision needs, and a wall of text
 * before two buttons trains people to press the nearest one. What it acts on is in the question.
 */
@Composable
private fun ToolApprovalCard(
    pending: PendingToolApproval,
    onResolve: (ToolApprovalDecision) -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Glass.cornerMedium),
        alpha = Glass.BUBBLE_ALPHA,
        tint = MaterialTheme.colorScheme.primaryContainer,
        blur = false,
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Allow Bram to ${pending.toolName}${approvalTarget(pending)}?",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (pending.recovered) {
                // Worth one line: this call was read out of the model's prose rather than marked as
                // a call. On its own that is a caveat; with a fetched page in the conversation it
                // is the reason the question is being asked at all, so say which.
                Text(
                    if (pending.untrustedContext) {
                        "Read from the reply text, and this chat contains a fetched page."
                    } else {
                        "Read from the reply text, not a marked call."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { onResolve(ToolApprovalDecision.ALLOW_ONCE) }) { Text("Allow once") }
                TextButton(onClick = { onResolve(ToolApprovalDecision.ALLOW_ALWAYS) }) { Text("Always") }
                TextButton(onClick = { onResolve(ToolApprovalDecision.DENY) }) { Text("Refuse") }
            }
        }
    }
}

/** The thing a call acts on, for the question. Shares its shape with the activity rows. */
private fun approvalTarget(pending: PendingToolApproval): String {
    val arguments = runCatching { JSONObject(pending.argumentsJson) }.getOrNull() ?: return ""
    val value = arguments.keys().asSequence()
        .mapNotNull { key -> arguments.opt(key)?.toString()?.takeIf(String::isNotBlank) }
        .firstOrNull() ?: return ""
    val shown = if (value.startsWith("http")) {
        runCatching { java.net.URI(value).host }.getOrNull()?.removePrefix("www.") ?: value
    } else {
        value
    }
    return " " + shown.take(40)
}

@Composable
private fun ActivityRow(entry: AgentActivity, number: Int? = null) {
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
                "${number?.let { "$it. " } ?: ""}$glyph ${entry.summary}${activityTarget(entry)}",
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

/**
 * The reasoning and tool steps a turn took, one row each, in the order they happened.
 *
 * A collapsed summary ("Thought 8s · 3 tools") hid the shape of the run behind a count, and the
 * shape is the interesting part while it is still running: thought, searched, read a page, thought
 * again. Each row is one line and opens for its detail, so several steps cost several lines rather
 * than several paragraphs.
 */
@Composable
private fun ActivityList(messageId: String, activity: List<AgentActivity>) {
    if (activity.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        activity.forEach { entry -> ActivityRow(entry) }
    }
}

/**
 * What a step acted on, for the end of its row.
 *
 * Read from the arguments rather than from a per-tool table, so a tool added later says something
 * useful without this having to learn about it. A URL shows its host, since the rest of a search
 * URL is noise on one line.
 */
private fun activityTarget(entry: AgentActivity): String {
    if (entry !is AgentActivity.ToolInvocation) return ""
    val arguments = runCatching { JSONObject(entry.argumentsJson) }.getOrNull() ?: return ""
    val value = arguments.keys().asSequence()
        .mapNotNull { key -> arguments.opt(key)?.toString()?.takeIf(String::isNotBlank) }
        .firstOrNull() ?: return ""
    val shown = if (value.startsWith("http")) {
        runCatching { java.net.URI(value).host }.getOrNull()?.removePrefix("www.") ?: value
    } else {
        value
    }
    return " · " + shown.take(48)
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
                Text(capability.kind.displayName, fontWeight = FontWeight.Medium)
                Text(capability.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EndpointCard(endpoint: RemoteEndpoint, onRemove: (String) -> Unit) {
    var expanded by rememberSaveable(endpoint.id) { mutableStateOf(false) }
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(endpoint.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        endpoint.modelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (expanded) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider()
                    Text(
                        "${endpoint.baseUrl} · ${formatTokens(endpoint.contextWindowTokens)} context",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onRemove(endpoint.id) }) { Text("Remove profile") }
                }
            }
        }
    }
}

@Composable
private fun McpServerCard(ui: McpServerUi, onRemove: (String) -> Unit) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ui.server.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    ui.server.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ui.error != null) {
                    Text(
                        ui.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        "${ui.toolCount} tool" + if (ui.toolCount == 1) "" else "s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = { onRemove(ui.server.id) }) { Text("Remove") }
        }
    }
}

@Composable
private fun SkillCard(
    pkg: SkillPackage,
    onEdit: (SkillPackage) -> Unit,
    onActivateDraft: (String) -> Unit,
    onRollback: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pkg.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                pkg.activeVersion?.let { version ->
                    Text(
                        "active v$version",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            pkg.versions.firstOrNull { it.version == pkg.activeVersion }?.let { active ->
                Text(active.description, style = MaterialTheme.typography.bodySmall)
            }
            pkg.draftVersion?.let { draft ->
                Text(
                    "Draft v$draft staged, not active yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row {
                TextButton(onClick = { onEdit(pkg) }) { Text("Edit") }
                if (pkg.draftVersion != null) {
                    TextButton(onClick = { onActivateDraft(pkg.id) }) { Text("Activate draft") }
                }
                TextButton(onClick = { onRollback(pkg.id) }) { Text("Roll back") }
                TextButton(onClick = { onRemove(pkg.id) }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun AutomationCard(
    automation: Automation,
    onSetEnabled: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(automation.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        automation.cron,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = automation.enabled,
                    onCheckedChange = { onSetEnabled(automation.id, it) },
                )
            }
            Text(
                automation.prompt.take(140),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val schedule = listOfNotNull(
                automation.lastRunAtEpochMillis?.let { "last: ${formatWhen(it)}" },
                automation.nextRunAtEpochMillis?.let { "next: ${formatWhen(it)}" },
            ).joinToString(" · ")
            Text(
                schedule.ifBlank { if (automation.enabled) "next: never (the schedule cannot fire)" else "paused" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = { onRemove(automation.id) }) { Text("Remove") }
            }
        }
    }
}

private fun formatWhen(epochMillis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        .format(java.util.Date(epochMillis))

@Composable
private fun MemoryCard(memory: MemoryRecord, onRemove: (String) -> Unit) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    memoryKindLabel(memory.kind),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "importance ${"%.1f".format(memory.importance)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(memory.text)
            Text(
                "remembered ${formatWhen(memory.createdAtEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = { onRemove(memory.id) }) { Text("Forget") }
            }
        }
    }
}

private fun memoryKindLabel(kind: MemoryKind): String = when (kind) {
    MemoryKind.SEMANTIC_FACT -> "Fact"
    MemoryKind.USER_INSTRUCTION -> "Instruction"
    MemoryKind.EPISODE -> "Episode"
    MemoryKind.WORKING_SUMMARY -> "Working summary"
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
internal fun SectionHeader(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        // Optional: a caption under every heading is read once and then becomes noise.
        subtitle.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A caption inside a card that names what the controls below it set. Quieter than a heading,
 * because it labels a row of chips rather than opening a section of its own.
 */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
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

/** When a message was sent. Today's messages show the time; older ones need the date too. */
private fun formatSentAt(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val sent = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    val now = java.util.Calendar.getInstance()
    val sameDay = sent.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
        sent.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "d MMM, HH:mm"
    return java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(java.util.Date(epochMillis))
}

private fun formatRate(rate: Double?): String = rate?.let { String.format(Locale.US, "%.1f tok/s", it) } ?: "measuring"
