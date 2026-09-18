package io.github.kurue.bram.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The task queue: what is scheduled, what is running, and what already finished — each task a
 * named session whose reply can be reopened from the library.
 */
@Composable
fun TasksScreen(
    state: AppUiState,
    onEnqueue: (String, String, Int?) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var composing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PanelHandle()
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader("Tasks", "${state.tasks.size} total")
            TextButton(onClick = { composing = !composing }) {
                Text(if (composing) "Done" else "New task")
            }
        }

        if (composing) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("What should the task do?") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it.filter(Char::isDigit).take(4) },
                        label = { Text("In how many minutes?") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = prompt.isNotBlank(),
                        onClick = {
                            val parsed = minutes.toIntOrNull()?.takeIf { it > 0 }
                            onEnqueue(name.ifBlank { prompt.lineSequence().firstOrNull()?.take(24) ?: "Task" }, prompt, parsed)
                            name = ""
                            prompt = ""
                            minutes = ""
                            composing = false
                        },
                    ) {
                        Text("Enqueue")
                    }
                }
                Text(
                    "Empty schedule runs as soon as the queue is free. Scheduled tasks wake the app when they come due; " +
                        "if no model is loaded they wait for one rather than being lost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.tasks.isEmpty()) {
                Text(
                    "Nothing queued. Schedule a task and Bram runs it as a named session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(4.dp),
                )
            }
            state.tasks.forEach { task ->
                TaskCard(task, onCancel, onRetry, onDelete)
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: AgentTask,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Glass.cornerMedium),
        alpha = Glass.DETAIL_ALPHA,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        buildString {
                            append(TaskStateLabel(task.state))
                            if (task.state == TaskState.QUEUED && task.scheduledAtEpochMillis != null) {
                                append(" · due ")
                                append(formatTime(task.scheduledAtEpochMillis))
                            } else {
                                task.startedAtEpochMillis?.let { append(" · started ").append(formatTime(it)) }
                                task.finishedAtEpochMillis?.let { append(" · ended ").append(formatTime(it)) }
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (task.state) {
                            TaskState.FAILED -> MaterialTheme.colorScheme.error
                            TaskState.SUCCEEDED -> MaterialTheme.colorScheme.primary
                            TaskState.DEFERRED -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (task.state) {
                        TaskState.QUEUED, TaskState.RUNNING -> TextButton(onClick = { onCancel(task.id) }) {
                            Text("Cancel")
                        }
                        TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED, TaskState.DEFERRED ->
                            TextButton(onClick = { onRetry(task.id) }) {
                                Text("Run again")
                            }
                    }
                    TextButton(onClick = { onDelete(task.id) }) {
                        Text("Delete", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            task.resultSummary?.let { summary ->
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            task.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (task.activityLog.isNotEmpty()) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    task.activityLog.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun TaskStateLabel(state: TaskState): String = state.label

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMillis))
