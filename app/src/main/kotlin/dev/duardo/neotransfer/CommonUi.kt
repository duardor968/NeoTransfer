package dev.duardo.neotransfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.core.Money
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun PageHeader(title: String, back: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_arrow_back), "Volver") }
        Text(title, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        trailing()
    }
}

@Composable
internal fun BrandArtwork(resource: Int, size: Dp, padding: Dp = 10.dp) {
    Box(Modifier.size(size).background(Color(0xFF0E0F12), RoundedCornerShape(if (size < 60.dp) 12.dp else 20.dp)).padding(padding)) {
        Image(painterResource(resource), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
internal fun Field(label: String, value: String, change: (String) -> Unit, type: KeyboardType = KeyboardType.Text,
                   readOnly: Boolean = false, secret: Boolean = false, trailing: (@Composable () -> Unit)? = null,
                   visualTransformation: VisualTransformation = VisualTransformation.None) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        readOnly = readOnly, keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (secret) PasswordVisualTransformation() else visualTransformation,
        trailingIcon = trailing, shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(focusedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            focusedBorderColor = MaterialTheme.colorScheme.onSecondaryContainer, cursorColor = MaterialTheme.colorScheme.onSecondaryContainer))
}

@Composable
internal fun Primary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = enabled, shape = MaterialTheme.shapes.large) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 6.dp))
    }
}

@Composable
internal fun EmptyAction(text: String, label: String, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = click) { Text(label) }
    }
}

@Composable
internal fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun SectionTitle(title: String, action: String? = null, click: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (action != null) TextButton(onClick = click) { Text(action) }
    }
}

@Composable
internal fun ActionRow(label: String, icon: Int, subtitle: String? = null, enabled: Boolean = true, click: () -> Unit) {
    ListItem(headlineContent = { Text(label) }, supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(painterResource(icon), null) },
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = click),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

@Composable
internal fun SearchField(value: String, change: (String) -> Unit, label: String) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), placeholder = { Text(label) }, singleLine = true,
        shape = MaterialTheme.shapes.large, leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { change("") }) { Icon(painterResource(R.drawable.ic_close), "Borrar búsqueda") } })
}

// Keep an excess character: an invalid paste must not silently become another valid account.
internal fun digits(text: String, max: Int) = text.filterNot { it.isWhitespace() || it == '-' }.take(max + 1)
internal fun decimalInput(text: String, previous: String): String = text.replace(',', '.').let {
    if (it.matches(Regex("[0-9]{0,12}(?:\\.[0-9]{0,2})?"))) it else previous
}
internal fun validAmount(text: String) = text.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?")) && text.toBigDecimalOrNull()?.signum() == 1
internal fun amountText(amount: BigDecimal): String = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-CU")).apply {
    minimumFractionDigits = 2; maximumFractionDigits = 2
}.format(amount)
internal fun formatMoney(money: Money) = "${amountText(money.amount)} ${money.currency}"
internal fun dateText(time: Instant) = DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.forLanguageTag("es"))
    .withZone(ZoneId.systemDefault()).format(time)
