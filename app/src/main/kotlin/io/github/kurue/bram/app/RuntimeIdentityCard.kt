package io.github.kurue.bram.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The values a hardware recipe has to name about this build, somewhere they can be selected and
 * copied. Writing a recipe used to mean knowing the engine commit and having a way to hash a 1.6 GB
 * file; both are already known here, so they are shown in full rather than truncated.
 */
@Composable
internal fun RuntimeIdentityCard(lines: List<RuntimeIdentityLine>) {
    GlassSurface(Modifier.fillMaxWidth().testTag("runtime-identity")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Runtime identity", fontWeight = FontWeight.SemiBold)
            Text(
                "What a hardware recipe has to name about this build. Select a value to copy it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lines.forEach { line ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        line.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer { Text(line.value, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
