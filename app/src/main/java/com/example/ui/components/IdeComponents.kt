package com.example.ui.components

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalIdeColors
import java.io.File

@Composable
fun IdePanel(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  val ideColors = LocalIdeColors.current
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, ideColors.border),
  ) {
    Column(modifier = Modifier.padding(12.dp), content = content)
  }
}

@Composable
fun IdeSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  trailing: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontWeight = FontWeight.SemiBold,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (trailing != null) {
      Spacer(Modifier.width(8.dp))
      trailing()
    }
  }
}

@Composable
fun StatusPill(
  text: String,
  color: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.extraSmall,
    color = color.copy(alpha = 0.14f),
    border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = color,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }
}

@Composable
fun ConfirmActionDialog(
  title: String,
  message: String,
  confirmLabel: String = "Confirm",
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title, style = MaterialTheme.typography.titleMedium) },
    text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(confirmLabel)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPreviewDialog(
  entryHtmlPath: String,
  onDismiss: () -> Unit,
) {
  val consoleLogs = remember { mutableStateListOf<String>() }
  val entryFile = remember(entryHtmlPath) { File(entryHtmlPath) }
  var webViewRef by remember { mutableStateOf<WebView?>(null) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier.fillMaxSize().padding(12.dp),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, LocalIdeColors.current.border),
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Live Web Runtime", style = MaterialTheme.typography.titleSmall)
            Text(
              entryFile.name,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          IconButton(onClick = { webViewRef?.reload() }) {
            Icon(Icons.Default.Refresh, contentDescription = "Reload web preview")
          }
          IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Close web preview")
          }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
          AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
              WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                webChromeClient =
                  object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                      consoleLogs += "[${msg.messageLevel()}] ${msg.message()} (line ${msg.lineNumber()})"
                      return true
                    }
                  }
                tag = entryFile.absolutePath
                loadUrl("file://${entryFile.absolutePath}")
                webViewRef = this
              }
            },
            update = { view ->
              webViewRef = view
              if (view.tag != entryFile.absolutePath) {
                view.tag = entryFile.absolutePath
                view.loadUrl("file://${entryFile.absolutePath}")
              }
            },
          )
        }

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .border(1.dp, LocalIdeColors.current.border)
            .background(MaterialTheme.colorScheme.background)
            .padding(8.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("JS Console", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (consoleLogs.isNotEmpty()) {
              TextButton(onClick = { consoleLogs.clear() }) {
                Text("Clear", style = MaterialTheme.typography.labelSmall)
              }
            }
          }
          Spacer(Modifier.height(4.dp))
          if (consoleLogs.isEmpty()) {
            Text(
              "No console output yet.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
              items(consoleLogs) { line ->
                Text(
                  text = line,
                  fontFamily = JetBrainsMonoFontFamily,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onBackground,
                )
              }
            }
          }
        }
      }
    }
  }
}
