package dev.leonardo.ocbeacon.ui.components.voice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.data.api.voice.ShowFrame
import dev.leonardo.ocbeacon.data.api.voice.ShowView
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* ── Show-view renderer (Seam 2, Compose port of omo-pulse ShowView.tsx) ──
   Pure rendering of a bridge ShowFrame: no business logic, no lifecycle.
   Selection taps bubble up via onSelect(contextTag, index). */

/** Label text for a collection entry: raw string or {label|text|name|title}. */
internal fun entryText(entry: JsonElement?): String {
    if (entry == null || entry is JsonNull) return ""
    if (entry is JsonPrimitive) return entry.content
    if (entry is JsonObject) {
        for (key in listOf("label", "text", "name", "title")) {
            val value = entry[key]
            if (value is JsonPrimitive && value.isString) return value.content
        }
    }
    return ""
}

internal fun entryStatus(entry: JsonElement?): String? {
    val value = (entry as? JsonObject)?.get("status")
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
}

internal fun elements(payload: JsonObject, key: String): List<JsonElement?> =
    (payload[key] as? JsonArray)?.toList() ?: emptyList()

internal fun stringField(payload: JsonObject, key: String): String? =
    (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Table columns: union of row-object keys in first-appearance order. */
internal fun tableColumns(rows: List<JsonElement?>): List<String> {
    val columns = mutableListOf<String>()
    for (row in rows) {
        val obj = row as? JsonObject ?: continue
        for (key in obj.keys) if (key !in columns) columns.add(key)
    }
    return columns
}

internal fun cellText(row: JsonElement?, column: String): String =
    if (row is JsonObject) entryText(row[column]) else entryText(row)

internal fun progressDone(payload: JsonObject, steps: Int): Int {
    val raw = (payload["done"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return 0
    return raw.toInt().coerceIn(0, steps)
}

internal fun progressPercent(payload: JsonObject): Int? =
    (payload["percent"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        ?.toInt()?.coerceIn(0, 100)

internal data class ComparisonSide(val label: String, val items: List<String>)

internal fun comparisonSide(payload: JsonObject, key: String, fallbackLabel: String): ComparisonSide {
    val obj = payload[key] as? JsonObject
    return ComparisonSide(
        label = obj?.let { stringField(it, "label") } ?: fallbackLabel,
        items = obj?.let { elements(it, "items") }?.map { entryText(it) } ?: emptyList(),
    )
}

internal data class DiffLine(val kind: String?, val text: String)

internal fun diffLines(payload: JsonObject): List<DiffLine> =
    elements(payload, "lines").map { line ->
        val obj = line as? JsonObject
        DiffLine(
            kind = obj?.let { stringField(it, "kind") },
            text = obj?.let { entryText(it["text"]) } ?: entryText(line),
        )
    }

/* ── Per-view renderers ── */

@Composable
private fun CardView(frame: ShowFrame) {
    val entry = remember(frame) { elements(frame.payload, "items").firstOrNull() }
    val status = entryStatus(entry)
    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
        Text(entryText(entry), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
        if (status != null) AssistChip(onClick = {}, label = { Text(status) })
    }
}

@Composable
private fun ListView(frame: ShowFrame, onSelect: ((String, Int) -> Unit)?) {
    val items = remember(frame) { elements(frame.payload, "items") }
    Column {
        items.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onSelect != null) {
                            Modifier.clickable { onSelect(frame.contextTag, index) }
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = SpacingTokens.SM.dp),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
            ) {
                Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(entryText(entry), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (index < items.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun TableView(frame: ShowFrame, onSelect: ((String, Int) -> Unit)?) {
    val rows = remember(frame) { elements(frame.payload, "rows") }
    val columns = remember(frame) { tableColumns(rows) }
    Column {
        Row(Modifier.padding(vertical = SpacingTokens.XS.dp)) {
            columns.forEach { column ->
                Text(
                    column,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onSelect != null) {
                            Modifier.clickable { onSelect(frame.contextTag, index) }
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = SpacingTokens.XS.dp),
            ) {
                columns.forEachIndexed { columnIndex, column ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
                        if (columnIndex == 0) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(cellText(row, column), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceView(frame: ShowFrame, onSelect: ((String, Int) -> Unit)?) {
    val options = remember(frame) { elements(frame.payload, "options") }
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
        options.forEachIndexed { index, option ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onSelect != null) {
                            Modifier.clickable { onSelect(frame.contextTag, index) }
                        } else {
                            Modifier
                        }
                    ),
            ) {
                Row(
                    Modifier.padding(SpacingTokens.MD.dp),
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
                ) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(entryText(option), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ProgressView(frame: ShowFrame) {
    val steps = remember(frame) { elements(frame.payload, "steps").mapIndexed { i, s -> entryText(s).ifEmpty { "Step ${i + 1}" } } }
    val done = remember(frame) { progressDone(frame.payload, steps.size) }
    val percent = remember(frame) { progressPercent(frame.payload) }
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
                Text(if (index < done) "✓" else "○", color = if (index < done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    step,
                    color = if (index < done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        val count = when {
            steps.isNotEmpty() -> "${done}/${steps.size}"
            percent != null -> "$percent%"
            else -> null
        }
        if (count != null) Text(count, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ComparisonView(frame: ShowFrame) {
    val left = remember(frame) { comparisonSide(frame.payload, "left", "left") }
    val right = remember(frame) { comparisonSide(frame.payload, "right", "right") }
    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.MD.dp)) {
        listOf(left, right).forEach { side ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
                Text(side.label, style = MaterialTheme.typography.titleSmall)
                side.items.forEach { item ->
                    Text(item, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun DiffView(frame: ShowFrame) {
    val lines = remember(frame) { diffLines(frame.payload) }
    Column {
        lines.forEach { line ->
            val color = when (line.kind) {
                "add" -> MaterialTheme.colorScheme.primary
                "del" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                line.text,
                color = color,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun UnknownViewFallback(frame: ShowFrame) {
    // Additive tolerance: unknown views from a newer bridge render as a
    // neutral fallback card with the raw payload instead of throwing.
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)) {
        Text(
            androidx.compose.ui.res.stringResource(dev.leonardo.ocbeacon.R.string.voice_show_fallback),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            frame.payload.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


/** Root: renders any [ShowFrame]; taps on choice/list/table rows → onSelect. */
@Composable
fun ShowViewCard(
    frame: ShowFrame,
    onSelect: ((contextTag: String, index: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(SpacingTokens.LG.dp),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
        ) {
            Text(frame.title, style = MaterialTheme.typography.titleMedium)
            when (val view = frame.view) {
                ShowView.Known.CARD -> CardView(frame)
                ShowView.Known.LIST -> ListView(frame, onSelect)
                ShowView.Known.TABLE -> TableView(frame, onSelect)
                ShowView.Known.CHOICE -> ChoiceView(frame, onSelect)
                ShowView.Known.PROGRESS -> ProgressView(frame)
                ShowView.Known.COMPARISON -> ComparisonView(frame)
                ShowView.Known.DIFF -> DiffView(frame)
                is ShowView.UnknownView -> UnknownViewFallback(frame)
            }
        }
    }
}
