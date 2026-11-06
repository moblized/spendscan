package com.moblized.spendscan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.moblized.spendscan.R
import com.moblized.spendscan.data.ThemeChoice
import com.moblized.spendscan.server.ServerConfig
import com.moblized.spendscan.ui.theme.LocalSlip
import com.moblized.spendscan.ui.theme.Type

@Composable
fun SettingsScreen(
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    receiptCount: Int,
    onExport: () -> Unit,
    version: String,
    updateStatus: String?,
    onCheckUpdates: () -> Unit,
    server: ServerConfig,
    onServer: (ServerConfig) -> Unit,
    serverModels: List<String>,
    serverStatus: String?,
    onFindModels: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    serverUrlRevision: Int = 0,
) {
    val c = LocalSlip.current
    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            CounterIcon(R.drawable.ic_back, "Back", onBack)
            Text("Settings", style = Type.lead, color = c.onCounter)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
        ) {
            Slip {
                Text("Appearance", style = Type.strong, color = c.ink)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Phone", theme == ThemeChoice.SYSTEM, { onTheme(ThemeChoice.SYSTEM) }, onPaper = true, modifier = Modifier.weight(1f))
                    Chip("Light", theme == ThemeChoice.LIGHT, { onTheme(ThemeChoice.LIGHT) }, onPaper = true, modifier = Modifier.weight(1f))
                    Chip("Dark", theme == ThemeChoice.DARK, { onTheme(ThemeChoice.DARK) }, onPaper = true, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
                Text("Phone follows your phone's dark mode setting. The app icon changes to match.", style = Type.small, color = c.inkFaded)

                SlipRule()

                ServerSection(server, onServer, serverModels, serverStatus, onFindModels, serverUrlRevision)

                SlipRule()

                Text("Your receipts", style = Type.strong, color = c.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (server.isSet) {
                        "Receipts and photos are kept on this phone. Photos of new scans also go to your server. The app only goes online for that and to check for updates."
                    } else {
                        "Receipts and photos are kept on this phone. The app only goes online to check for updates."
                    },
                    style = Type.body,
                    color = c.inkFaded,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "If your phone backs up to Google, your receipt list goes with it. Photos don't.",
                    style = Type.small,
                    color = c.inkFaded,
                )
                Spacer(Modifier.height(10.dp))
                PaperButton(
                    if (receiptCount == 0) "Nothing to export yet" else "Export ${count(receiptCount, "receipt")} as CSV",
                    onExport,
                    Modifier.fillMaxWidth(),
                    enabled = receiptCount > 0,
                )
                Spacer(Modifier.height(4.dp))
                Text("Makes files of your receipts, items and fill-ups, for a spreadsheet.", style = Type.small, color = c.inkFaded)

                SlipRule()

                Text("Updates", style = Type.strong, color = c.ink)
                Spacer(Modifier.height(4.dp))
                Text("SpendScan $version", style = Type.body, color = c.inkFaded)
                Spacer(Modifier.height(10.dp))
                PaperButton("Check for updates", onCheckUpdates, Modifier.fillMaxWidth())
                if (updateStatus != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(updateStatus, style = Type.small, color = c.ink)
                }
            }
        }
    }
}

/** Your own language model, for reading scans. Leave it empty and the phone reads them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerSection(
    server: ServerConfig,
    onServer: (ServerConfig) -> Unit,
    models: List<String>,
    status: String?,
    onFindModels: () -> Unit,
    urlRevision: Int,
) {
    val c = LocalSlip.current
    // What's typed stays here, so saving never gets in the way of the keyboard. It only resets when the app corrects the address.
    var url by rememberSaveable(urlRevision) { mutableStateOf(server.url) }
    var key by rememberSaveable { mutableStateOf(server.key) }

    Text("Language model server", style = Type.strong, color = c.ink)
    Spacer(Modifier.height(4.dp))
    Text(
        "Reads your scans with your own model. Leave the address empty and this phone reads them.",
        style = Type.body,
        color = c.inkFaded,
    )
    Spacer(Modifier.height(10.dp))
    PaperField(
        "Server address",
        url,
        {
            url = it
            onServer(server.copy(url = it))
        },
        keyboardType = KeyboardType.Uri,
        placeholder = "http://192.168.1.20:11434",
    )
    if (server.sendsInTheClear) {
        Spacer(Modifier.height(4.dp))
        Text(
            "This address isn't encrypted, so your photos could be read on the way there. Use https:// outside your home network.",
            style = Type.small,
            color = c.onHighlighter,
            modifier = Modifier.background(c.highlighter, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
    PaperField(
        "API key, if it asks for one",
        key,
        {
            key = it
            onServer(server.copy(key = it))
        },
        keyboardType = KeyboardType.Password,
        visualTransformation = PasswordVisualTransformation(),
    )
    Spacer(Modifier.height(10.dp))
    PaperButton("Find models", onFindModels, Modifier.fillMaxWidth())
    if (status != null) {
        Spacer(Modifier.height(6.dp))
        Text(status, style = Type.small, color = c.ink)
    }
    if (models.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            models.forEach { name ->
                Chip(name, server.model == name, { onServer(server.copy(model = name)) }, onPaper = true)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    PaperField("Model", server.model, { onServer(server.copy(model = it.trim())) }, placeholder = "qwen3-vl:8b")
}
