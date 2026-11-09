package dev.minicam.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.data.Network
import dev.minicam.mobile.ui.theme.CamColors
import dev.minicam.mobile.ui.theme.CamDimens

/**
 * "Local | Remote" (docs/adr/0013): one tap moves between the home-network address and the
 * Cloudflare address of the same server. A side without an address asks for it first.
 */
@Composable
fun NetworkSwitch(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    var adding by remember { mutableStateOf<Network?>(null) }
    val shape = RoundedCornerShape(CamDimens.Radius)
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(shape)
            .border(1.dp, CamColors.Border, shape)
            .background(CamColors.Bg),
    ) {
        listOf(
            Triple(Network.LOCAL, "Local", viewModel.localAddress),
            Triple(Network.REMOTE, "Remote", viewModel.remoteAddress),
        ).forEach { (network, label, address) ->
            val selected = viewModel.activeNetwork == network
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(if (selected) CamColors.Accent else CamColors.Bg)
                    .semantics {
                        this.selected = selected
                        contentDescription = if (address == null) "Add ${label.lowercase()} address" else label
                    }
                    .clickable(enabled = !selected, role = Role.Tab) {
                        if (address == null) adding = network else viewModel.switchNetwork(network)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (address == null) "+ $label" else label,
                    color = if (selected) CamColors.OnAccent else if (address == null) CamColors.Muted else CamColors.Text,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
    adding?.let { network -> AddAddressDialog(viewModel, network, onDismiss = { adding = null }) }
}

@Composable
private fun AddAddressDialog(viewModel: AppViewModel, network: Network, onDismiss: () -> Unit) {
    val local = network == Network.LOCAL
    var text by remember { mutableStateOf(if (local) "http://" else "https://") }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    val discovered by viewModel.discovery.servers.collectAsState()
    DisposableEffect(searching) {
        if (searching) viewModel.discovery.start()
        onDispose { viewModel.discovery.stop() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CamColors.Surface,
        titleContentColor = CamColors.Text,
        textContentColor = CamColors.Muted,
        title = {
            Text(if (local) "Add local address" else "Add remote address", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (local) {
                        "The address of this same minicam on your home Wi-Fi."
                    } else {
                        "The Cloudflare address of this same minicam, used away from home."
                    },
                    fontSize = 13.sp,
                )
                CamTextField(
                    "Address",
                    text,
                    { text = it },
                    keyboardType = KeyboardType.Uri,
                    placeholder = if (local) "http://192.168.1.20:8080" else "https://cam.example.com",
                )
                if (local) {
                    DiscoveryList(
                        searching = searching,
                        servers = discovered.map { it.name to it.baseUrl },
                        onToggle = { searching = !searching },
                        onPick = {
                            text = it
                            searching = false
                        },
                    )
                }
                error?.let { Text(it, color = CamColors.Danger, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = viewModel.addAddress(text, network)
                if (error == null) onDismiss()
            }) { Text("Save and switch", color = CamColors.AccentHover, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = CamColors.Muted) } },
    )
}
