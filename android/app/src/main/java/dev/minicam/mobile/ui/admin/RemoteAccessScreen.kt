package dev.minicam.mobile.ui.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.data.CloudflareAccount
import dev.minicam.mobile.data.CloudflareClient
import dev.minicam.mobile.data.CloudflareException
import dev.minicam.mobile.data.CloudflareSetup
import dev.minicam.mobile.data.CloudflareStep
import dev.minicam.mobile.data.CloudflareZone
import dev.minicam.mobile.data.MinicamException
import dev.minicam.mobile.data.TunnelHostname
import dev.minicam.mobile.data.TunnelState
import dev.minicam.mobile.data.TunnelStatus
import dev.minicam.mobile.data.removeTunnel
import dev.minicam.mobile.data.setUpTunnel
import dev.minicam.mobile.data.tunnelStatus
import dev.minicam.mobile.data.waitForTunnel
import dev.minicam.mobile.ui.AppViewModel
import dev.minicam.mobile.ui.CamTextField
import dev.minicam.mobile.ui.PrimaryButton
import dev.minicam.mobile.ui.Screen
import dev.minicam.mobile.ui.SecondaryButton
import dev.minicam.mobile.ui.theme.CamColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Admin → Remote access (docs/adr/0009): sets up the Cloudflare Tunnel, DNS record and
 * Access application, then hands minicam the tunnel. The API token lives only in this
 * screen's state: it is never stored, logged or sent to minicam.
 */
@Composable
fun RemoteAccessScreen(viewModel: AppViewModel) {
    val api = viewModel.api ?: return
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<TunnelStatus?>(null) }
    var unavailable by remember { mutableStateOf<String?>(null) }
    var apiToken by remember { mutableStateOf("") }
    var accounts by remember { mutableStateOf(emptyList<CloudflareAccount>()) }
    var account by remember { mutableStateOf<CloudflareAccount?>(null) }
    var zones by remember { mutableStateOf(emptyList<CloudflareZone>()) }
    var zone by remember { mutableStateOf<CloudflareZone?>(null) }
    var label by remember { mutableStateOf("cam") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<String?>(null) }

    fun describe(e: Exception): String = when (e) {
        CloudflareException.ZeroTrustRequired ->
            "This Cloudflare account has no Zero Trust organization. In the Cloudflare dashboard open " +
                "Zero Trust, pick a team name and the free plan, then try again."
        is CloudflareException -> "Cloudflare: ${e.message}"
        else -> viewModel.describe(e)
    }

    /** Runs [work] with the screen busy; reports failures in the form. */
    fun run(work: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            success = null
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                busy = false
                progress = null
            }
        }
    }

    LaunchedEffect(api) {
        if (api.config.isWan) {
            unavailable = "Remote access can only be set up while you're connected to your home network."
            return@LaunchedEffect
        }
        try {
            status = api.tunnelStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (_: MinicamException.NotFound) {
            unavailable = "This minicam installation can't set up remote access by itself. " +
                "Use the Raspberry Pi OS image, or follow the manual steps in its documentation."
        } catch (e: Exception) {
            error = describe(e)
        }
    }

    fun loadAccounts() = run {
        val client = CloudflareClient(apiToken.trim())
        accounts = client.accounts()
        account = accounts.singleOrNull()
        zones = emptyList()
        zone = null
        account?.let { zones = client.zones(it.id) }
        zone = zones.singleOrNull()
        if (accounts.isEmpty()) error = "This token can't see any Cloudflare account."
    }

    fun selectAccount(selected: CloudflareAccount) {
        account = selected
        run {
            zones = CloudflareClient(apiToken.trim()).zones(selected.id)
            zone = zones.singleOrNull()
        }
    }

    fun setUp() {
        val chosenAccount = account ?: return
        val chosenZone = zone ?: return
        val hostname = TunnelHostname.make(label, chosenZone.name)
        val email = viewModel.user?.email
        if (hostname == null) {
            error = "Use letters, digits and dashes for the name (no dots)."
            return
        }
        if (email == null) {
            error = "Reload the app and try again."
            return
        }
        run {
            val setup = CloudflareSetup(CloudflareClient(apiToken.trim())).run(chosenAccount, chosenZone, hostname, email) {
                progress = when (it) {
                    CloudflareStep.ZERO_TRUST -> "Checking Zero Trust…"
                    CloudflareStep.TUNNEL -> "Creating the tunnel…"
                    CloudflareStep.ROUTE -> "Routing $hostname to minicam…"
                    CloudflareStep.DNS -> "Adding the DNS record…"
                    CloudflareStep.ACCESS -> "Protecting it with Cloudflare Access…"
                }
            }
            progress = "Installing the tunnel on the device (about a minute)…"
            api.setUpTunnel(setup)
            apiToken = ""
            val result = api.waitForTunnel()
            status = result
            when (result.state) {
                TunnelState.RUNNING -> success = "Remote access is ready at https://$hostname"
                TunnelState.ERROR -> error = result.message ?: "The device could not start the tunnel."
                else -> success = "Still installing on the device. Check back in a minute."
            }
        }
    }

    fun remove() = run {
        api.removeTunnel()
        status = api.waitForTunnel()
        success = "Remote access removed. The Cloudflare tunnel, DNS record and Access application stay in your account."
    }

    AdminScaffold("Remote access", onBack = { viewModel.screen = Screen.CAMERAS }) {
        unavailable?.let { message ->
            Section("Remote access") { Hint(message) }
            return@AdminScaffold
        }

        Section("Status") {
            val current = status
            when {
                current == null && error == null -> RowsSkeleton(rows = 1, trailing = false)
                current != null -> {
                    val label = when (current.state) {
                        TunnelState.NONE -> "Not set up"
                        TunnelState.INSTALLING -> "Installing…"
                        TunnelState.RUNNING -> "Running"
                        TunnelState.ERROR -> "Failed"
                    }
                    Text(label, color = CamColors.Text, fontSize = 14.sp)
                    current.hostname?.let { Hint("https://$it") }
                    current.message?.let { Hint(it) }
                    if (current.state == TunnelState.RUNNING || current.state == TunnelState.ERROR) {
                        SecondaryButton("Remove remote access", enabled = !busy, onClick = ::remove)
                    }
                }
            }
        }

        Section("Set up with Cloudflare") {
            Hint(
                "You need a domain on Cloudflare. Create an API token at dash.cloudflare.com → My Profile → " +
                    "API Tokens with: Account · Cloudflare Tunnel: Edit, Access: Apps and Policies: Edit, " +
                    "Access: Organizations, Identity Providers, and Groups: Read; Zone · DNS: Edit, Zone: Read. " +
                    "The token is used only for this setup and is never saved.",
            )
            CamTextField("Cloudflare API token", apiToken, { apiToken = it }, secret = true, monospace = true)
            SecondaryButton("Load accounts", enabled = apiToken.isNotBlank() && !busy, onClick = ::loadAccounts)
            if (accounts.size > 1) {
                OptionPicker("Account", accounts.map { it to it.name }, account ?: accounts.first(), onSelect = ::selectAccount)
            }
            if (zones.isNotEmpty()) {
                if (zones.size > 1) {
                    OptionPicker("Domain", zones.map { it to it.name }, zone ?: zones.first()) { zone = it }
                }
                CamTextField("Name", label, { label = it }, keyboardType = KeyboardType.Uri)
                zone?.let { Hint("Address: https://${TunnelHostname.make(label, it.name) ?: "…"}") }
                PrimaryButton("Set up remote access", enabled = zone != null && !busy, busy = busy, onClick = ::setUp)
            }
            progress?.let { Spinner(it) }
            FormMessage(error, success)
        }
    }
}
