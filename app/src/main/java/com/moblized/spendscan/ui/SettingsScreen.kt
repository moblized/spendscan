package com.moblized.spendscan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moblized.spendscan.data.ThemeChoice
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
    modifier: Modifier = Modifier,
) {
    val c = LocalSlip.current
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Text("Settings", style = Type.lead, color = c.onCounter, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 12.dp))
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

                Text("Your receipts", style = Type.strong, color = c.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Receipts and photos are kept on this phone. The app only goes online to check for updates.",
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
                Text("Makes one file of receipts and one of items, for a spreadsheet.", style = Type.small, color = c.inkFaded)

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
