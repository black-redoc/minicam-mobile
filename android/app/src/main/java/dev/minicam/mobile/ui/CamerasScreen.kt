package dev.minicam.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.ui.theme.CamColors

@Composable
fun CamerasScreen(viewModel: AppViewModel) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(Modifier.fillMaxSize().background(CamColors.Bg).safeDrawingPadding()) {
        Header(onRefresh = viewModel::refresh, onLogout = viewModel::logout)

        if (viewModel.cameras.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    viewModel.errorMessage ?: "No cameras yet. Ask an admin to grant you one.",
                    color = CamColors.Muted,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(viewModel.cameras, key = { it.name }) { camera ->
                    Box(Modifier.fillMaxWidth().clickable { viewModel.selectedCamera = camera }) {
                        Card(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(camera.name, color = CamColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                if (camera.canUsePtz) Text("PTZ", color = CamColors.Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The desktop top bar (`h-14`, slate-900, bottom border) with its "More options" menu. */
@Composable
private fun Header(onRefresh: () -> Unit, onLogout: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandTitle()
        Box(Modifier.weight(1f))
        TextButton(onClick = onRefresh) { Text("Refresh", color = CamColors.Muted, fontSize = 13.sp) }
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("⋮", color = CamColors.Muted, fontSize = 18.sp) }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.background(CamColors.Surface),
            ) {
                DropdownMenuItem(
                    text = { Text("Log out", color = CamColors.Danger) },
                    onClick = {
                        menuOpen = false
                        onLogout()
                    },
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(CamColors.Surface))
}
