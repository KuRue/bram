package io.github.kurue.bram.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kurue.bram.core.domain.MonthActivity
import io.github.kurue.bram.core.domain.RunOrigin
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Home: where Bram opens. The month's work at the top, then anything running, then recent chats
 * and tasks with when they last changed. It reads only the journal and conversation index, so it
 * shows without loading a model or reaching a network.
 */
@Composable
internal fun HomeScreen(
    state: AppUiState,
    onNewChat: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onReturnToChat: () -> Unit,
    onShiftMonth: (Long) -> Unit,
    onRefresh: () -> Unit,
) {
    // Totals move when a reply or a task finishes; re-read them then, not on a timer.
    LaunchedEffect(state.isGenerating, state.tasks.count { it.isFinished }) { onRefresh() }

    val tasksByConversation = state.tasks.associateBy { it.conversationId.value }
    val recent = state.conversations
        .filter { it.messageCount > 0 }
        .sortedByDescending { it.updatedAtEpochMillis }
        .take(RECENT_LIMIT)

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("home"),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 92.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MonthCard(state, onShiftMonth) }

        val now = buildList {
            if (state.isGenerating) add("Replying" to onReturnToChat)
            if (state.pendingApproval != null) add("Waiting for your approval" to onReturnToChat)
            state.tasks.filter { it.state == TaskState.RUNNING }
                .forEach { task -> add("Running task: ${task.displayName}" to { onOpenConversation(task.conversationId.value) }) }
            state.tasks.filter { it.state == TaskState.DEFERRED }
                .forEach { task -> add("Waiting for a model: ${task.displayName}" to { onOpenConversation(task.conversationId.value) }) }
        }
        if (now.isNotEmpty()) {
            item { SectionLabel("Now") }
            items(now) { (label, open) ->
                GlassSurface(Modifier.fillMaxWidth().clickable(onClick = open)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("●", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
                        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ChevronRightIcon(MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(20.dp))
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Recent", Modifier.weight(1f))
                TextButton(onClick = onNewChat, modifier = Modifier.testTag("home-new-chat")) { Text("New chat") }
            }
        }
        if (recent.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "No conversations yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onNewChat) { Text("Start a chat") }
                }
            }
        } else {
            items(recent, key = { it.id.value }) { summary ->
                val task = tasksByConversation[summary.id.value]
                RecentRow(
                    title = task?.displayName ?: summary.title,
                    kind = if (task != null) "Task" else "Chat",
                    status = task?.state?.label,
                    updatedAt = summary.updatedAtEpochMillis,
                    onClick = { onOpenConversation(summary.id.value) },
                )
            }
        }
    }
}

@Composable
private fun MonthCard(state: AppUiState, onShiftMonth: (Long) -> Unit) {
    // The configuration's locale, read observably, so a language change re-renders the card.
    val locale = LocalConfiguration.current.locales[0]
    val month = state.homeMonth
    val activity = state.homeActivity
    var details by rememberSaveable { mutableStateOf(false) }
    GlassSurface(Modifier.fillMaxWidth().testTag("home-month")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onShiftMonth(-1) },
                    modifier = Modifier.size(40.dp).semantics { contentDescription = "Previous month" },
                ) { Text("‹", style = MaterialTheme.typography.titleLarge) }
                Text(
                    "${month.month.getDisplayName(TextStyle.FULL, locale)} ${month.year}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                val atCurrent = !month.isBefore(java.time.YearMonth.now())
                IconButton(
                    onClick = { onShiftMonth(1) },
                    enabled = !atCurrent,
                    modifier = Modifier.size(40.dp).semantics { contentDescription = "Next month" },
                ) { Text("›", style = MaterialTheme.typography.titleLarge) }
            }
            if (activity == null) {
                Text("Reading activity…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("Generated tokens", activity.generatedTokens?.let(::compactCount) ?: "—", Modifier.weight(1f))
                Stat("Completed runs", activity.completedRuns.toString(), Modifier.weight(1f))
                Stat("Tool calls", activity.toolCalls?.toString() ?: "—", Modifier.weight(1f))
            }
            if (activity.runsPerDay.isNotEmpty()) DayChart(activity)
            if (activity.isEmpty) {
                Text("No runs this month.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!activity.complete && !activity.isEmpty) {
                val since = state.activityRecordedSince?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                        .format(DateTimeFormatter.ofPattern("MMM d", locale))
                }
                // Say which gap it is: older rows, or a runtime that did not report usage.
                val notes = buildList {
                    if (activity.legacyRuns > 0) {
                        add(since?.let { "full records since $it" } ?: "some runs predate full records")
                    }
                    if (activity.unmeasuredRuns > 0) {
                        add("${activity.unmeasuredRuns} ${if (activity.unmeasuredRuns == 1) "run" else "runs"} without token usage")
                    }
                }
                Text(
                    "Partial: ${notes.joinToString("; ")}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!activity.isEmpty) {
                TextButton(
                    onClick = { details = !details },
                    contentPadding = PaddingValues(0.dp),
                ) { Text(if (details) "Hide details" else "Details") }
            }
            if (details) {
                MetricRow("Input tokens", activity.inputTokens?.let(::compactCount) ?: "—")
                MetricRow("Generated on this phone", compactCount(activity.localGeneratedTokens))
                MetricRow("Generated on servers", compactCount(activity.remoteGeneratedTokens))
                MetricRow("Chat turns", (activity.completedByOrigin[RunOrigin.CHAT] ?: 0).toString())
                MetricRow("Tasks", (activity.completedByOrigin[RunOrigin.TASK] ?: 0).toString())
                MetricRow("Failed or cancelled", activity.unfinishedRuns.toString())
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Completed runs per day across the month: one bar per day, empty days left as a faint mark. */
@Composable
private fun DayChart(activity: MonthActivity) {
    val days = activity.month.lengthOfMonth()
    val max = (activity.runsPerDay.values.maxOrNull() ?: 1).coerceAtLeast(1)
    val bar = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val description = "Completed runs per day, most on one day: $max"
    Canvas(Modifier.fillMaxWidth().height(44.dp).semantics { contentDescription = description }) {
        val slot = size.width / days
        val width = slot * 0.62f
        for (day in 1..days) {
            val count = activity.runsPerDay[LocalDate.of(activity.month.year, activity.month.month, day)] ?: 0
            val height = if (count == 0) 2.dp.toPx() else (size.height * count / max).coerceAtLeast(3.dp.toPx())
            drawRoundRect(
                color = if (count == 0) faint else bar,
                topLeft = Offset(slot * (day - 1) + (slot - width) / 2, size.height - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 2, width / 2),
            )
        }
    }
}

@Composable
private fun RecentRow(title: String, kind: String, status: String?, updatedAt: Long, onClick: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("home-recent")) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(30.dp)) {
                Text(if (kind == "Task") "◇" else "▤", color = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(kind, status, relativeTime(updatedAt)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            ChevronRightIcon(MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(20.dp))
        }
    }
}

/** "1.2k", "34k", "1.2M": enough precision for a monthly total at a glance. */
internal fun compactCount(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 10_000 -> String.format(Locale.getDefault(), "%.1fk", value / 1_000.0)
    value < 1_000_000 -> "${value / 1_000}k"
    else -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000.0)
}

/** "just now", "12 min ago", "3 h ago", "Yesterday", then a date. */
internal fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val minutes = (now - epochMillis) / 60_000
    val day = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        day == today -> "${minutes / 60} h ago"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        else -> day.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
    }
}

private const val RECENT_LIMIT = 12
