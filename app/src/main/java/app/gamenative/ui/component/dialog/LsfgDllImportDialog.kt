package app.gamenative.ui.component.dialog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.utils.LsfgVkManager

@Composable
fun LsfgDllImportDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val resolution = remember(refreshKey) { LsfgVkManager.resolveDll(context) }
    val hasImported = remember(refreshKey) { LsfgVkManager.importedDllFile(context) != null }

    val pickDllLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                LsfgVkManager.importDll(context, input)
            }
            refreshKey++
        }
    }

    val statusLine = when (resolution.source) {
        LsfgVkManager.DllSource.IMPORTED -> stringResource(R.string.settings_lsfg_status_imported)
        LsfgVkManager.DllSource.STEAM -> stringResource(R.string.settings_lsfg_status_steam)
        LsfgVkManager.DllSource.NONE -> stringResource(R.string.settings_lsfg_status_none)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_lsfg_title)) },
        text = {
            Column {
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_lsfg_import_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { pickDllLauncher.launch(arrayOf("*/*")) }) {
                Text(
                    text = stringResource(
                        if (hasImported) R.string.settings_lsfg_override_dll
                        else R.string.settings_lsfg_import_dll,
                    ),
                )
            }
        },
        dismissButton = {
            if (hasImported) {
                TextButton(onClick = {
                    LsfgVkManager.removeImportedDll(context)
                    refreshKey++
                }) {
                    Text(text = stringResource(R.string.settings_lsfg_remove_imported))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.close))
            }
        },
    )
}
