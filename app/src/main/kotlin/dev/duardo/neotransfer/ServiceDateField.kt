package dev.duardo.neotransfer

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val serviceDateFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)

/** Parse exactly the value accepted by FieldKind.DATE; invalid text is never coerced. */
internal fun parseServiceDate(value: String): LocalDate? = runCatching { LocalDate.parse(value, serviceDateFormat) }.getOrNull()
internal fun formatServiceDate(date: LocalDate): String = date.format(serviceDateFormat)
internal fun isServiceDateSelectable(date: LocalDate, earliest: LocalDate?, latest: LocalDate?): Boolean =
    (earliest == null || !date.isBefore(earliest)) && (latest == null || !date.isAfter(latest))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServiceDateField(label: String, value: String, change: (String) -> Unit,
                              earliest: LocalDate? = null, latest: LocalDate? = null) {
    var open by remember { mutableStateOf(false) }
    val parsed = remember(value) { parseServiceDate(value) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f).heightIn(min = 54.dp)) {
            Icon(painterResource(R.drawable.ic_calendar_month), null)
            Spacer(Modifier.width(12.dp))
            Text(if (value.isBlank()) label else "$label: ${parsed?.let(::formatServiceDate) ?: value}")
        }
        if (value.isNotEmpty()) IconButton(onClick = { change("") }) {
            Icon(painterResource(R.drawable.ic_close), "Borrar $label")
        }
    }
    if (open) {
        val selectable = remember(earliest, latest) { object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = isServiceDateSelectable(
                Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate(), earliest, latest)
            override fun isSelectableYear(year: Int): Boolean =
                (earliest == null || year >= earliest.year) && (latest == null || year <= latest.year)
        } }
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = parsed?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            selectableDates = selectable,
        )
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { millis ->
                    val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    if (isServiceDateSelectable(date, earliest, latest)) change(formatServiceDate(date))
                }
                open = false
            }, enabled = picker.selectedDateMillis?.let { isServiceDateSelectable(
                Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(), earliest, latest) } == true) { Text("Elegir fecha") }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } }) {
            DatePicker(picker, title = { Text(label, Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium) })
        }
    }
}
