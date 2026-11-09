/*
 * Copyright 2026 OpenAndroidAssistant contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openandroidassistant.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.openandroidassistant.data.settings.ExtraHeader
import org.openandroidassistant.data.settings.headerProblem
import org.openandroidassistant.data.settings.maskSecret

/** A settings page: large collapsing title, back arrow, scrolling content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
  val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
  Scaffold(
    modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
    topBar = {
      LargeTopAppBar(
        title = { Text(title) },
        navigationIcon = {
          if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        scrollBehavior = scroll,
      )
    },
  ) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
      content = content,
    )
  }
}

/** [SettingsPage] for a long list, with room for a header that stays pinned while it scrolls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsLazyPage(title: String, onBack: (() -> Unit)?, content: LazyListScope.() -> Unit) {
  val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
  Scaffold(
    modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
    topBar = {
      LargeTopAppBar(
        title = { Text(title) },
        navigationIcon = {
          if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        scrollBehavior = scroll,
      )
    },
  ) { padding ->
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp), content = content)
  }
}

@Composable
fun SectionHeader(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
  )
}

/** Plain explanatory text between rows. */
@Composable
fun Explanation(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
  )
}

@Composable
fun NavRow(title: String, summary: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
fun SwitchRow(title: String, summary: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  Row(
    Modifier.fillMaxWidth()
      .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
      .padding(horizontal = 24.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
  }
}

/** One option of a single-choice group. */
data class Choice<T>(val value: T, val title: String, val summary: String? = null, val enabled: Boolean = true)

@Composable
fun <T> ChoiceGroup(choices: List<Choice<T>>, selected: T, onSelect: (T) -> Unit) {
  Column {
    for (choice in choices) {
      Row(
        Modifier.fillMaxWidth()
          .selectable(selected = choice.value == selected, enabled = choice.enabled, role = Role.RadioButton) { onSelect(choice.value) }
          .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RadioButton(selected = choice.value == selected, onClick = null, enabled = choice.enabled, modifier = Modifier.padding(horizontal = 8.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
          Text(choice.title, style = MaterialTheme.typography.bodyLarge, color = if (choice.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
          choice.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
      }
    }
  }
}

/** A text value edited in a dialog. */
@Composable
fun TextRow(
  title: String,
  value: String,
  placeholder: String = "Not set",
  singleLine: Boolean = true,
  keyboardType: KeyboardType = KeyboardType.Text,
  help: String? = null,
  validate: (String) -> String? = { null },
  onSave: (String) -> Unit,
) {
  var editing by remember { mutableStateOf(false) }
  Row(
    Modifier.fillMaxWidth().clickable { editing = true }.padding(horizontal = 24.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(
        value.ifBlank { placeholder },
        style = MaterialTheme.typography.bodyMedium,
        color = if (value.isBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
      )
    }
  }
  if (editing) {
    var draft by remember { mutableStateOf(value) }
    val problem = validate(draft.trim())
    AlertDialog(
      onDismissRequest = { editing = false },
      title = { Text(title) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          help?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
          OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            isError = problem != null,
            supportingText = problem?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
          )
        }
      },
      confirmButton = { TextButton(onClick = { onSave(draft.trim()); editing = false }, enabled = problem == null) { Text("Save") } },
      dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
    )
  }
}

/** An API key: masked, with a show button, edited in a dialog. */
@Composable
fun SecretRow(title: String, value: String?, help: String? = null, onSave: (String?) -> Unit) {
  var editing by remember { mutableStateOf(false) }
  var shown by remember { mutableStateOf(false) }
  Row(
    Modifier.fillMaxWidth().clickable { editing = true }.padding(start = 24.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(
        when {
          value == null -> "Not set"
          shown -> value
          else -> maskSecret(value)
        },
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (value != null) FontFamily.Monospace else FontFamily.Default),
        color = if (value == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (value != null) {
      IconButton(onClick = { shown = !shown }) {
        Icon(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (shown) "Hide" else "Show")
      }
    }
  }
  if (editing) {
    var draft by remember { mutableStateOf(value.orEmpty()) }
    var reveal by remember { mutableStateOf(false) }
    AlertDialog(
      onDismissRequest = { editing = false },
      title = { Text(title) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          help?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
          OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
              IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
          )
        }
      },
      confirmButton = { TextButton(onClick = { onSave(draft.trim().ifEmpty { null }); editing = false }) { Text("Save") } },
      dismissButton = {
        Row {
          if (value != null) TextButton(onClick = { onSave(null); editing = false }) { Text("Remove") }
          TextButton(onClick = { editing = false }) { Text("Cancel") }
        }
      },
    )
  }
}

/** A username and password (HTTP basic auth), edited together in a dialog. */
@Composable
fun LoginRow(title: String, username: String?, password: String?, onSave: (username: String?, password: String?) -> Unit) {
  var editing by remember { mutableStateOf(false) }
  Row(
    Modifier.fillMaxWidth().clickable { editing = true }.padding(horizontal = 24.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(
        username ?: "Not set",
        style = MaterialTheme.typography.bodyMedium,
        color = if (username == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  if (editing) {
    var user by remember { mutableStateOf(username.orEmpty()) }
    var pass by remember { mutableStateOf(password.orEmpty()) }
    var reveal by remember { mutableStateOf(false) }
    val problem = if (':' in user) "No colons in the username." else null
    AlertDialog(
      onDismissRequest = { editing = false },
      title = { Text(title) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username") },
            singleLine = true,
            isError = problem != null,
            supportingText = problem?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
              IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
          )
        }
      },
      confirmButton = {
        TextButton(onClick = { onSave(user.trim().ifEmpty { null }, pass.ifEmpty { null }); editing = false }, enabled = problem == null) { Text("Save") }
      },
      dismissButton = {
        Row {
          if (username != null) TextButton(onClick = { onSave(null, null); editing = false }) { Text("Remove") }
          TextButton(onClick = { editing = false }) { Text("Cancel") }
        }
      },
    )
  }
}

/** Request headers as name/value pairs, listed in a dialog where each can be added, edited or removed. */
@Composable
fun HeaderListRow(title: String, headers: List<ExtraHeader>, onSave: (List<ExtraHeader>) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Row(
    Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 24.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(
        when (headers.size) {
          0 -> "None"
          1 -> "1 header"
          else -> "${headers.size} headers"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (headers.isEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  if (!open) return
  // The header being edited: an index into [headers], or headers.size for a new one.
  var editing by remember { mutableStateOf<Int?>(null) }
  AlertDialog(
    onDismissRequest = { open = false },
    title = { Text(title) },
    text = {
      Column {
        if (headers.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        headers.forEachIndexed { i, h ->
          Row(Modifier.fillMaxWidth().clickable { editing = i }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(h.name, style = MaterialTheme.typography.bodyLarge)
              Text(
                maskSecret(h.value),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconButton(onClick = { onSave(headers.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Filled.Close, "Remove") }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } },
    dismissButton = { TextButton(onClick = { editing = headers.size }) { Text("Add header") } },
  )
  editing?.let { i ->
    HeaderDialog(headers.getOrNull(i), onDismiss = { editing = null }) { h ->
      onSave(if (i < headers.size) headers.toMutableList().also { it[i] = h } else headers + h)
      editing = null
    }
  }
}

@Composable
private fun HeaderDialog(header: ExtraHeader?, onDismiss: () -> Unit, onSave: (ExtraHeader) -> Unit) {
  var name by remember { mutableStateOf(header?.name.orEmpty()) }
  var value by remember { mutableStateOf(header?.value.orEmpty()) }
  val problem = headerProblem(name.trim(), value.trim())
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (header == null) "Add header" else "Header") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Name") },
          placeholder = { Text("X-API-Key") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = value,
          onValueChange = { value = it },
          label = { Text("Value") },
          singleLine = true,
          // An empty name isn't worth flagging until something is typed.
          isError = problem != null && name.isNotBlank(),
          supportingText = problem?.takeIf { name.isNotBlank() }?.let { { Text(it) } },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = { TextButton(onClick = { onSave(ExtraHeader(name.trim(), value.trim())) }, enabled = problem == null) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/** A highlighted box with a concrete example request. */
@Composable
fun ExampleBox(label: String, quote: String) {
  Surface(
    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
      Text(quote, style = MaterialTheme.typography.bodyLarge)
    }
  }
}

/** A short single choice as a segmented button row. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
  androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
    options.forEachIndexed { i, (value, label) ->
      SegmentedButton(
        selected = value == selected,
        onClick = { onSelect(value) },
        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, options.size),
        icon = {},
      ) {
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}

@Composable
fun CardBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Surface(
    modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Column(Modifier.padding(PaddingValues(20.dp)), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
  }
}
