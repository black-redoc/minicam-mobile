package dev.minicam.mobile.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.minicam.mobile.data.AccessHandoff
import dev.minicam.mobile.data.CameraSummary
import dev.minicam.mobile.data.CredentialKey
import dev.minicam.mobile.data.CredentialStore
import dev.minicam.mobile.data.KeystoreCredentialStore
import dev.minicam.mobile.data.LanDiscovery
import dev.minicam.mobile.data.MinicamApi
import dev.minicam.mobile.data.MinicamException
import dev.minicam.mobile.data.ServerConfig
import dev.minicam.mobile.data.ServerConfigException
import dev.minicam.mobile.data.SetupStatus
import dev.minicam.mobile.data.User
import dev.minicam.mobile.data.requestSetupCode
import dev.minicam.mobile.data.setupStatus
import kotlinx.coroutines.launch

/**
 * Top-level screens besides login and the camera view. Recordings is for everyone (ADR 0005);
 * the admin ones need `is_admin` (ADR 0004).
 */
enum class Screen { CAMERAS, RECORDINGS, CAMERA_SETTINGS, AI_DETECTION, ADMIN, REMOTE_ACCESS, CENTRAL_DASHBOARD }

/** App-wide state: which server, whether we have a session, the camera list. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store: CredentialStore = KeystoreCredentialStore(application)

    var api: MinicamApi? by mutableStateOf(null)
        private set
    var signedIn by mutableStateOf(false)
        private set
    var cameras by mutableStateOf(emptyList<CameraSummary>())
        private set

    /** False until the first camera request of this session succeeds (skeleton rows, spec 007). */
    var camerasLoaded by mutableStateOf(false)
        private set
    var selectedCamera by mutableStateOf<CameraSummary?>(null)
    var screen by mutableStateOf(Screen.CAMERAS)

    /** The signed-in account; null until login or the first `GET /api/auth/me`. */
    var user by mutableStateOf<User?>(null)
        private set
    var serverUrlText by mutableStateOf(store.get(CredentialKey.SERVER_URL) ?: "https://")
    var errorMessage by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    var accessTokenPresent by mutableStateOf(store.get(CredentialKey.ACCESS_TOKEN) != null)
        private set

    /** The server's first-run setup status; null until checked, or for WAN hosts. */
    var setupStatus by mutableStateOf<SetupStatus?>(null)
        private set

    /** Login screen shows "Create admin account" instead of "Sign in" (docs/adr/0008). */
    var creatingAdmin by mutableStateOf(false)
    var setupCodeMessage by mutableStateOf<String?>(null)
        private set

    /** `_minicam._tcp` browser for "Find minicam on my network". */
    val discovery = LanDiscovery(application)

    /** Pending Custom Tabs login; its `state` must come back in the callback. */
    private var pendingHandoff: AccessHandoff? = null

    init {
        store.get(CredentialKey.SERVER_URL)?.let { saved ->
            runCatching { ServerConfig.parse(saved) }.getOrNull()?.let { config ->
                api = MinicamApi(config, store).also { signedIn = it.hasSession }
            }
        }
    }

    val needsAccessLogin: Boolean
        get() {
            val config = runCatching { ServerConfig.parse(serverUrlText) }.getOrNull() ?: return false
            return config.isWan && !accessTokenPresent
        }

    /** Validates the URL and (re)creates the API client for it. */
    fun applyServerUrl(): MinicamApi? = try {
        val config = ServerConfig.parse(serverUrlText)
        if (api?.config != config) {
            store.set(CredentialKey.ACCESS_TOKEN, null)
            accessTokenPresent = false
            store.clearSession()
            api = MinicamApi(config, store)
        }
        store.set(CredentialKey.SERVER_URL, config.origin())
        api
    } catch (e: ServerConfigException) {
        errorMessage = when (e.reason) {
            ServerConfigException.Reason.INSECURE_REMOTE_HOST ->
                "Plain http:// is only allowed on your local network. Use https://."
            else -> "Enter a valid server address, e.g. https://cam.example.com"
        }
        null
    }

    /** Returns the URL to open in Custom Tabs, or null if the server URL is invalid. */
    fun startAccessLogin(): String? {
        val api = applyServerUrl() ?: return null
        return AccessHandoff().also { pendingHandoff = it }.handoffUrl(api.config)
    }

    fun completeAccessLogin(callback: String) {
        val handoff = pendingHandoff ?: return
        pendingHandoff = null
        runCatching { handoff.tokenFromCallback(callback) }
            .onSuccess {
                store.set(CredentialKey.ACCESS_TOKEN, it)
                accessTokenPresent = true
                errorMessage = null
            }
            .onFailure { errorMessage = "Cloudflare Access login failed. Try again." }
    }

    fun login(email: String, password: String) {
        val api = applyServerUrl() ?: return
        launchBusy {
            user = api.login(email, password)
            signedIn = true
            cameras = api.cameras()
            camerasLoaded = true
        }
    }

    /**
     * Asks a LAN server whether it still needs its first admin, and switches the login
     * screen to "Create admin account" if so. WAN hosts are never set up from the app.
     */
    fun checkSetup() {
        val config = runCatching { ServerConfig.parse(serverUrlText) }.getOrNull()
        if (config == null || config.isWan) {
            setupStatus = null
            creatingAdmin = false
            return
        }
        viewModelScope.launch {
            val status = runCatching { MinicamApi(config, store).setupStatus() }.getOrNull()
            if (runCatching { ServerConfig.parse(serverUrlText) }.getOrNull() != config) return@launch
            setupStatus = status
            creatingAdmin = status != null && status.setupRequired
            setupCodeMessage = null
        }
    }

    /** Has the device's LED blink a new setup code. */
    fun requestSetupCode() {
        val api = applyServerUrl() ?: return
        launchBusy {
            val claim = api.requestSetupCode()
            setupCodeMessage = "The green LED is blinking a ${claim.digits}-digit code. " +
                "Count the blinks of each digit; a longer pause separates digits, an even longer one starts " +
                "again. It is valid for ${claim.expiresIn / 60} minutes."
        }
    }

    /** Creates the first admin (with the LED code when the device asks for it) and signs in. */
    fun completeSetup(email: String, password: String, setupCode: String?) {
        val api = applyServerUrl() ?: return
        launchBusy {
            user = api.completeSetup(email, password, setupCode)
            signedIn = true
            setupStatus = null
            creatingAdmin = false
            setupCodeMessage = null
            cameras = api.cameras()
            camerasLoaded = true
        }
    }

    fun refresh() {
        val api = api ?: return
        launchBusy {
            if (user == null) user = api.me()
            cameras = api.cameras()
            camerasLoaded = true
        }
    }

    fun logout() {
        val api = api
        viewModelScope.launch {
            api?.logout()
            cameras = emptyList()
            camerasLoaded = false
            selectedCamera = null
            screen = Screen.CAMERAS
            user = null
            signedIn = false
        }
    }

    /** Central error mapping, shared by every screen. */
    fun handle(error: Throwable) {
        errorMessage = describe(error)
    }

    /**
     * User-facing message for [error]. A lost session or Access token also signs out here,
     * so every screen gets the same behavior (spec 001 AC4).
     */
    fun describe(error: Throwable): String = when (error) {
        MinicamException.InvalidCredentials -> "Invalid email or password"
        MinicamException.Unauthorized -> {
            signOutLocally()
            "Your session expired. Sign in again."
        }
        MinicamException.AccessRequired -> {
            store.set(CredentialKey.ACCESS_TOKEN, null)
            accessTokenPresent = false
            signOutLocally()
            "Sign in with Cloudflare Access again."
        }
        MinicamException.RateLimited -> "Too many attempts. Wait a few minutes."
        is MinicamException.Forbidden -> error.detail
        is MinicamException.Rejected -> error.detail
        MinicamException.NotFound -> "Not found or not allowed."
        is MinicamException.Server -> "The server failed (${error.code})."
        else -> "Can't reach the server."
    }

    private fun signOutLocally() {
        signedIn = false
        camerasLoaded = false
        selectedCamera = null
        screen = Screen.CAMERAS
        user = null
    }

    private fun launchBusy(work: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            errorMessage = null
            try {
                work()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                handle(e)
            } finally {
                busy = false
            }
        }
    }
}
