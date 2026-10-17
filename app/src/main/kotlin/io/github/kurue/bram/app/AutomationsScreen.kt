package io.github.kurue.bram.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kurue.bram.core.domain.Automation
import io.github.kurue.bram.core.domain.Schedule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Automations as their own place: a list of named, scheduled prompts with when each runs next and
 * how it last went; a detail with Run now, pause, and the history of its runs (each opens the chat
 * it ran in); and an editor with plain schedules and a preview. Runs go through the task queue.
 */
@Composable
internal fun AutomationsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSave: (name: String, cron: String, prompt: String, id: String?) -> Unit,
    onRemove: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onRunNow: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val open = state.automations.firstOrNull { it.id == openId }
    val locale = LocalConfiguration.current.locales[0]

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PanelHandle()
        when {
            editing -> AutomationEditor(
                existing = open,
                locale = locale,
                onCancel = { editing = false },
                onSave = { name, cron, prompt ->
                    onSave(name, cron, prompt, open?.id)
                    editing = false
                },
            )
            open != null -> AutomationDetail(
                automation = open,
                runs = state.tasks.filter { it.automationId == open.id }.sortedByDescending { it.createdAtEpochMillis },
                status = state.automationStatus,
                locale = locale,
                onBack = { openId = null },
                onEdit = { editing = true },
                onRunNow = { onRunNow(open.id) },
                onSetEnabled = { onSetEnabled(open.id, it) },
                onRemove = {
                    openId = null
                    onRemove(open.id)
                },
                onOpenConversation = onOpenConversation,
            )
            else -> {
                DestinationHeader("Automations", "Capabilities", onBack)
                Text(
                    "Prompts that run on a schedule, through the task queue, with the same routing and approvals as other work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.automationStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                if (state.automations.isEmpty()) {
                    EmptyDestination("No automations", "Create one when Bram should do something on a schedule.")
                } else {
                    state.automations.forEach { automation ->
                        val last = state.tasks.filter { it.automationId == automation.id }.maxByOrNull { it.createdAtEpochMillis }
                        AutomationRow(automation, last, locale) { openId = automation.id }
                    }
                }
                Button(
                    onClick = { openId = null; editing = true },
                    modifier = Modifier.fillMaxWidth().testTag("automation-new"),
                ) { Text("New automation") }
            }
        }
    }
}

private fun whenText(millis: Long, locale: Locale): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", locale))

@Composable
private fun AutomationRow(automation: Automation, last: AgentTask?, locale: Locale, onClick: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("automation-row")) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(automation.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    Schedule.describe(Schedule.fromCron(automation.cron), locale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    listOfNotNull(
                        automation.nextRunAtEpochMillis.let { next ->
                            when {
                                !automation.enabled -> "Paused"
                                next != null -> "Next ${whenText(next, locale)}"
                                else -> "Never runs: check the schedule"
                            }
                        },
                        last?.let { "Last: ${it.state.label} · ${relativeTime(it.finishedAtEpochMillis ?: it.createdAtEpochMillis)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!automation.enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                )
            }
            ChevronRightIcon(MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AutomationDetail(
    automation: Automation,
    runs: List<AgentTask>,
    status: String?,
    locale: Locale,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onRunNow: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("‹ Automations") }
    Text(automation.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    status?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRunNow, modifier = Modifier.weight(1f).testTag("automation-run-now")) { Text("Run now") }
        OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Edit") }
    }
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Schedule.describe(Schedule.fromCron(automation.cron), locale), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (automation.enabled) "On schedule" else "Paused: Run now still works",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = automation.enabled,
                    onCheckedChange = onSetEnabled,
                    modifier = Modifier.testTag("automation-enabled"),
                )
            }
            if (automation.enabled) {
                Schedule.upcoming(automation.cron, System.currentTimeMillis()).forEach { next ->
                    Text("• ${whenText(next, locale)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "Android may start a run late, especially while the phone is idle; a time shown is when it was asked to run.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SectionLabel("Instructions")
    Text(automation.prompt, style = MaterialTheme.typography.bodyMedium)
    SectionLabel("History")
    if (runs.isEmpty()) {
        Text("No runs yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        runs.take(20).forEach { run ->
            GlassSurface(Modifier.fillMaxWidth().clickable { onOpenConversation(run.conversationId.value) }.testTag("automation-run")) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(run.state.label, fontWeight = FontWeight.Medium)
                        Text(
                            listOfNotNull(
                                whenText(run.createdAtEpochMillis, locale),
                                run.error?.take(80),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    ChevronRightIcon(MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(20.dp))
                }
            }
        }
    }
    TextButton(onClick = { confirmDelete = true }) { Text("Delete automation", color = MaterialTheme.colorScheme.error) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${automation.name}?") },
            text = { Text("It stops running. Its past runs and their chats are kept.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onRemove() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private enum class Repeat(val label: String) { DAILY("Every day"), WEEKDAYS("Weekdays"), WEEKLY("Weekly"), MONTHLY("Monthly"), CUSTOM("Custom") }

@Composable
private fun AutomationEditor(
    existing: Automation?,
    locale: Locale,
    onCancel: () -> Unit,
    onSave: (name: String, cron: String, prompt: String) -> Unit,
) {
    val initial = existing?.let { Schedule.fromCron(it.cron) } ?: Schedule.Daily(9, 0)
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var prompt by rememberSaveable { mutableStateOf(existing?.prompt.orEmpty()) }
    var repeat by rememberSaveable {
        mutableStateOf(
            when (initial) {
                is Schedule.Daily -> Repeat.DAILY
                is Schedule.Weekdays -> Repeat.WEEKDAYS
                is Schedule.Weekly -> Repeat.WEEKLY
                is Schedule.Monthly -> Repeat.MONTHLY
                is Schedule.Custom -> Repeat.CUSTOM
            },
        )
    }
    fun hourOf(s: Schedule) = when (s) { is Schedule.Daily -> s.hour; is Schedule.Weekdays -> s.hour; is Schedule.Weekly -> s.hour; is Schedule.Monthly -> s.hour; else -> 9 }
    fun minuteOf(s: Schedule) = when (s) { is Schedule.Daily -> s.minute; is Schedule.Weekdays -> s.minute; is Schedule.Weekly -> s.minute; is Schedule.Monthly -> s.minute; else -> 0 }
    var hour by rememberSaveable { mutableStateOf(hourOf(initial).toString()) }
    var minute by rememberSaveable { mutableStateOf("%02d".format(minuteOf(initial))) }
    var weekday by rememberSaveable { mutableStateOf((initial as? Schedule.Weekly)?.day ?: 1) }
    var monthDay by rememberSaveable { mutableStateOf(((initial as? Schedule.Monthly)?.day ?: 1).toString()) }
    var custom by rememberSaveable { mutableStateOf((initial as? Schedule.Custom)?.cron ?: initial.cron) }

    val h = hour.toIntOrNull()?.takeIf { it in 0..23 }
    val m = minute.toIntOrNull()?.takeIf { it in 0..59 }
    val d = monthDay.toIntOrNull()?.takeIf { it in 1..28 }
    val cron: String? = when (repeat) {
        Repeat.CUSTOM -> custom.trim().takeIf { it.isNotEmpty() }
        else -> if (h == null || m == null) null else when (repeat) {
            Repeat.DAILY -> Schedule.Daily(h, m).cron
            Repeat.WEEKDAYS -> Schedule.Weekdays(h, m).cron
            Repeat.WEEKLY -> Schedule.Weekly(weekday, h, m).cron
            Repeat.MONTHLY -> d?.let { Schedule.Monthly(it, h, m).cron }
            Repeat.CUSTOM -> null
        }
    }
    val preview = cron?.let { Schedule.upcoming(it, System.currentTimeMillis()) }.orEmpty()

    TextButton(onClick = onCancel, contentPadding = PaddingValues(0.dp)) { Text("‹ Cancel") }
    Text(if (existing == null) "New automation" else "Edit automation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("automation-name"))
    OutlinedTextField(
        prompt,
        { prompt = it },
        label = { Text("What Bram should do") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth().testTag("automation-prompt"),
    )
    SectionLabel("Repeats")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Repeat.entries.forEach { entry ->
            FilterChip(selected = repeat == entry, onClick = { repeat = entry }, label = { Text(entry.label) })
        }
    }
    if (repeat == Repeat.CUSTOM) {
        OutlinedTextField(
            custom,
            { custom = it },
            label = { Text("Cron") },
            supportingText = { Text("minute hour day month weekday, for example 0 9 * * 1-5") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        if (repeat == Repeat.WEEKLY) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(1, 2, 3, 4, 5, 6, 0)).forEach { day ->
                    FilterChip(
                        selected = weekday == day,
                        onClick = { weekday = day },
                        label = { Text(Schedule.dayName(day, locale).take(3)) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (repeat == Repeat.MONTHLY) {
                OutlinedTextField(
                    monthDay, { monthDay = it.filter(Char::isDigit).take(2) },
                    label = { Text("Day") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = d == null,
                    modifier = Modifier.width(88.dp),
                )
            }
            OutlinedTextField(
                hour, { hour = it.filter(Char::isDigit).take(2) },
                label = { Text("Hour") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = h == null,
                modifier = Modifier.width(88.dp),
            )
            Text(":", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                minute, { minute = it.filter(Char::isDigit).take(2) },
                label = { Text("Minute") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = m == null,
                modifier = Modifier.width(88.dp),
            )
        }
    }
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                cron?.let { Schedule.describe(Schedule.fromCron(it), locale) } ?: "Incomplete schedule",
                fontWeight = FontWeight.SemiBold,
            )
            if (preview.isEmpty()) {
                Text(
                    if (cron == null) "Fill in the time to see the next runs." else "This schedule never runs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                preview.forEach { Text("• ${whenText(it, locale)}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Button(
        onClick = { cron?.let { onSave(name, it, prompt) } },
        enabled = cron != null && preview.isNotEmpty() && name.isNotBlank() && prompt.isNotBlank(),
        modifier = Modifier.fillMaxWidth().testTag("automation-save"),
    ) { Text("Save") }
}
