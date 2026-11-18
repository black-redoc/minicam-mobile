package dev.minicam.mobile.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.minicam.mobile.data.AccessHandoff
import dev.minicam.mobile.data.CameraSummary
import dev.minicam.mobile.data.CentralStatusCache
import dev.minicam.mobile.data.CredentialKey
import dev.minicam.mobile.data.NotificationScheduleStore
import dev.minicam.mobile.data.CredentialStore
import dev.minicam.mobile.data.KeystoreCredentialStore
import dev.minicam.mobile.data.LanDiscovery
import dev.minicam.mobile.data.MinicamApi
import dev.minicam.mobile.data.MinicamException
import dev.minicam.mobile.data.ModelCatalogCache
import dev.minicam.mobile.data.Network
import dev.minicam.mobile.data.PairLink
import dev.minicam.mobile.data.PendingClips
import dev.minicam.mobile.data.PendingPairLink
import dev.minicam.mobile.data.RecordingCache
import dev.minicam.mobile.data.ServerAddresses
import dev.minicam.mobile.data.ServerConfig
import dev.minicam.mobile.data.ServerConfigException
import dev.minicam.mobile.data.SetupStatus
import dev.minicam.mobile.data.SystemUpdate
import dev.minicam.mobile.data.UpdateProgress
import dev.minicam.mobile.data.UpdateTracker
import dev.minicam.mobile.data.User
import dev.minicam.mobile.data.mobilePushStatus
import dev.minicam.mobile.data.network
import dev.minicam.mobile.data.requestSetupCode
import dev.minicam.mobile.data.setupStatus
import dev.minicam.mobile.data.startUpdate
import dev.minicam.mobile.data.systemUpdate
import dev.minicam.mobile.i18n.tr
import dev.minicam.mobile.push.FirebasePush
import dev.minicam.mobile.push.PushRegistration
import dev.minicam.mobile.push.PushSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Top-level screens besides login and the camera view. Recordings is for everyone (ADR 0005);
 * the admin ones need `is_admin` (ADR 0004).
 */
enum class Screen { CAMERAS, RECORDINGS, CAMERA_SETTINGS, AI_DETECTION, ADMIN, CENTRAL_DASHBOARD, SETTINGS }

/** App-wide state: which server, whether we have a session, the camera list. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store: CredentialStore = KeystoreCredentialStore(application)

    var api: MinicamApi? by mutableStateOf(null)
        private set

    /** Admins: the device's minicam version and whether a newer release can be installed (ADR 0017). */
    var systemUpdate by mutableStateOf<SystemUpdate?>(null)
        private set

    /** An update started from this phone, while it runs and once it ends. */
    var updateProgress by mutableStateOf<UpdateProgress?>(null)
        private set
    var updateError by mutableStateOf<String?>(null)
    var checkingUpdate by mutableStateOf(false)
        private set
    private var updateJob: Job? = null

    /** The YOLO model catalog, fetched once per session (ADR 0016). */
    val modelCatalogCache = ModelCatalogCache()
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

    /** A dashboard QR link opened before the app could use it (docs/adr/0011); memory only. */
    val pendingPairLink = PendingPairLink()

    /** `_minicam._tcp` browser for "Find minicam on my network". */
    val discovery = LanDiscovery(application)

    /** Local and remote address of the server, and which one is active (docs/adr/0013). */
    private val addresses = ServerAddresses(store)

    /** Shown by "Connect to dashboard" away from home, where minicam does not answer it. */
    val centralStatus = CentralStatusCache(store)

    /** Detection notifications on this phone (docs/adr/0007); hidden in builds without Firebase. */
    private val push = PushRegistration(store, FirebasePush.tokens(application))
    var pushSwitch by mutableStateOf(push.switchState(available = null))
        private set

    /** When this phone shows detection pushes (docs/adr/0015); edited in Admin. */
    val notificationSchedule = NotificationScheduleStore(store)

    /** One-off message about the switch, shown by the screen and then cleared. */
    var pushMessage by mutableStateOf<String?>(null)

    /** A camera to open from a tapped notification, once signed in and the list is loaded. */
    private var pendingCamera: String? = null
    var activeNetwork by mutableStateOf<Network?>(null)
        private set
    var localAddress by mutableStateOf<String?>(null)
        private set
    var remoteAddress by mutableStateOf<String?>(null)
        private set

    /** Pending Custom Tabs login; its `state` must come back in the callback. */
    private var pendingHandoff: AccessHandoff? = null

    init {
        addresses.active?.let { config ->
            api = MinicamApi(config, store).also { signedIn = it.hasSession }
        }
        syncAddresses()
    }

    private fun syncAddresses() {
        activeNetwork = addresses.active?.network
        localAddress = addresses.local?.origin()
        remoteAddress = addresses.remote?.origin()
        accessTokenPresent = store.get(CredentialKey.ACCESS_TOKEN) != null
    }

    val needsAccessLogin: Boolean
        get() {
            val config = runCatching { ServerConfig.parse(serverUrlText) }.getOrNull() ?: return false
            return config.isWan && !accessTokenPresent
        }

    /** Validates the URL and (re)creates the API client for it. */
    fun applyServerUrl(): MinicamApi? = try {
        val config = ServerConfig.parse(serverUrlText)
        // One of the saved pair keeps the session; any other address is a new server.
        addresses.use(config)
        if (api?.config != config) api = MinicamApi(config, store)
        syncAddresses()
        api
    } catch (e: ServerConfigException) {
        errorMessage = addressError(e)
        null
    }

    private fun addressError(e: ServerConfigException) = when (e.reason) {
        ServerConfigException.Reason.INSECURE_REMOTE_HOST ->
            tr("Plain http:// is only allowed on your local network. Use https://.")
        else -> tr("Enter a valid server address, e.g. https://cam.example.com")
    }

    /**
     * Uses the other saved address of the same server (docs/adr/0013). Nothing is sent to the
     * old one; the session and Access token are kept, so the list reloads without signing in.
     */
    fun switchNetwork(network: Network) {
        val config = addresses.switchTo(network) ?: return
        serverUrlText = config.origin()
        api = MinicamApi(config, store)
        errorMessage = null
        cameras = emptyList()
        camerasLoaded = false
        selectedCamera = null
        screen = Screen.CAMERAS
        syncAddresses()
        // Remote without an Access token goes back to the login screen's Access button.
        signedIn = store.get(CredentialKey.SESSION_COOKIE) != null && !addresses.needsAccessLogin(config)
        if (signedIn) refresh()
    }

    /** Saves the missing [network] address of this server and switches to it; null on success. */
    fun addAddress(text: String, network: Network): String? {
        val config = try {
            ServerConfig.parse(text)
        } catch (e: ServerConfigException) {
            return addressError(e)
        }
        if (config.network != network) {
            return if (network == Network.LOCAL) {
                tr("A local address starts with http://, e.g. http://192.168.1.20:8080")
            } else {
                tr("A remote address starts with https://")
            }
        }
        addresses.add(config)
        switchNetwork(network)
        return null
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
                // Switched to Remote with a session already: no need to sign in again.
                if (api?.hasSession == true) {
                    signedIn = true
                    refresh()
                }
            }
            .onFailure { errorMessage = tr("Cloudflare Access login failed. Try again.") }
    }

    /** Prefilled on the login screen, so signing back in only needs the password. */
    val lastEmail: String get() = store.get(CredentialKey.LAST_EMAIL).orEmpty()

    fun login(email: String, password: String) {
        val api = applyServerUrl() ?: return
        launchBusy {
            user = api.login(email, password)
            signedIn = true
            offerPairLink()
            cameras = api.cameras()
            onCamerasLoaded(api, newSession = true)
        }
    }

    /**
     * `minicam://pair?url=…&code=…` from the dashboard's QR (docs/adr/0011). Opens "Connect to
     * dashboard" with the form filled in; it never sends anything by itself.
     */
    fun openPairLink(link: String) {
        val parsed = PairLink.parse(link)
        if (parsed == null) {
            errorMessage = tr("That link is not a minicam dashboard code.")
            return
        }
        pendingPairLink.store(parsed)
        offerPairLink()
    }

    /** Opens the Connect screen for a pending link once signed in as an admin on the LAN. */
    private fun offerPairLink() {
        if (pendingPairLink.peek() == null) return
        val onLan = api?.config?.isWan == false
        if (signedIn && onLan && user?.isAdmin == true) {
            selectedCamera = null
            screen = Screen.CENTRAL_DASHBOARD
        } else {
            errorMessage = tr("To use the dashboard code, sign in as an admin while you're on the device's " +
                "home network. The code is kept for 10 minutes.")
        }
    }

    /**
     * Asks a LAN server whether it still needs its first admin, and switches the login
     * screen to "Create admin account" if so. WAN hosts are never set up from the app.
     * With [switchMode] false the user's own choice of form is kept (only the status refreshes).
     */
    fun checkSetup(switchMode: Boolean = true) {
        val config = runCatching { ServerConfig.parse(serverUrlText) }.getOrNull()
        if (config == null || config.isWan) {
            setupStatus = null
            if (switchMode) creatingAdmin = false
            return
        }
        viewModelScope.launch {
            val status = runCatching { MinicamApi(config, store).setupStatus() }.getOrNull()
            if (runCatching { ServerConfig.parse(serverUrlText) }.getOrNull() != config) return@launch
            setupStatus = status
            if (switchMode) creatingAdmin = status != null && status.setupRequired
            setupCodeMessage = null
        }
    }

    /** The login screen's Sign in / Create admin account switch; always offered, e.g. after re-flashing. */
    fun chooseCreateAdmin(create: Boolean) {
        creatingAdmin = create
        errorMessage = null
        // The device may have been re-flashed since the address was typed: ask again.
        if (create) checkSetup(switchMode = false)
    }

    /** Has the device's LED blink a new setup code. */
    fun requestSetupCode() {
        val api = applyServerUrl() ?: return
        launchBusy {
            val claim = api.requestSetupCode()
            setupCodeMessage = tr(
                "The green LED is blinking a %1\$d-digit code. " +
                    "Count the blinks of each digit; a longer pause separates digits, an even longer one starts " +
                    "again. It is valid for %2\$d minutes.",
                claim.digits,
                claim.expiresIn / 60,
            )
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
            offerPairLink()
            cameras = api.cameras()
            onCamerasLoaded(api, newSession = true)
        }
    }

    fun refresh() {
        val api = api ?: return
        launchBusy {
            if (user == null) user = api.me()
            offerPairLink()
            cameras = api.cameras()
            onCamerasLoaded(api, newSession = false)
            checkForUpdate()
        }
    }

    /**
     * Admins: asks the device for its version and the latest release (the device asks GitHub
     * at most every 6 hours; [refresh] asks it to check now). Quiet unless [refresh].
     */
    fun checkForUpdate(refresh: Boolean = false) {
        val api = api ?: return
        if (user?.isAdmin != true || updateJob?.isActive == true) return
        viewModelScope.launch {
            checkingUpdate = true
            if (refresh) updateError = null
            try {
                systemUpdate = api.systemUpdate(refresh)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (refresh) updateError = describe(e)
            } finally {
                checkingUpdate = false
            }
        }
    }

    /** Installs the offered release and follows it until the device runs it (or gives up). */
    fun installUpdate() {
        val api = api ?: return
        val version = systemUpdate?.latest ?: return
        if (updateJob?.isActive == true) return
        updateError = null
        updateJob = viewModelScope.launch {
            try {
                systemUpdate = api.startUpdate(version)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                updateError = describe(e)
                return@launch
            }
            val tracker = UpdateTracker(version, System.currentTimeMillis())
            var progress: UpdateProgress = UpdateProgress.Running(version, "requested", null)
            updateProgress = progress
            while (progress is UpdateProgress.Running) {
                delay(UpdateTracker.POLL_MILLIS)
                val now = System.currentTimeMillis()
                progress = try {
                    val status = api.systemUpdate() ?: throw MinicamException.InvalidResponse
                    systemUpdate = status
                    tracker.onStatus(status, now)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // minicam is restarting: no answer for a while is expected.
                    tracker.onUnreachable(now)
                }
                updateProgress = progress
            }
        }
    }

    private fun onCamerasLoaded(api: MinicamApi, newSession: Boolean) {
        camerasLoaded = true
        pendingCamera?.let { name ->
            cameras.firstOrNull { it.name == name }?.let { selectedCamera = it }
            pendingCamera = null
        }
        if (pushSwitch == PushSwitch.HIDDEN) return
        viewModelScope.launch {
            // A new session must own the token, or its logout would not stop the pushes.
            if (newSession && push.enabled) runCatching { push.refresh(api) }
            val available = runCatching { api.mobilePushStatus().available }.getOrDefault(false)
            pushSwitch = push.switchState(available)
        }
    }

    /** Opens [camera] from a notification tap; after sign-in if the session is gone (spec 012 AC10). */
    fun openCameraFromNotification(camera: String) {
        selectedCamera = null
        screen = Screen.CAMERAS
        val loaded = cameras.firstOrNull { it.name == camera }
        if (signedIn && loaded != null) selectedCamera = loaded else pendingCamera = camera
        if (signedIn && loaded == null) refresh()
    }

    /** The switch; the screen asks for `POST_NOTIFICATIONS` first on Android 13+. */
    fun setPushEnabled(on: Boolean) {
        val api = api ?: return
        viewModelScope.launch {
            try {
                if (on) push.enable(api) else push.disable(api)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                pushMessage = tr("Could not turn on notifications. %s", describe(e))
            }
            pushSwitch = push.switchState(available = true)
        }
    }

    fun logout() {
        val api = api
        // The screen changes at once; the server is told best-effort (docs/adr/0013).
        cameras = emptyList()
        camerasLoaded = false
        selectedCamera = null
        screen = Screen.CAMERAS
        user = null
        signedIn = false
        if (pushSwitch != PushSwitch.HIDDEN) pushSwitch = PushSwitch.UNAVAILABLE
        centralStatus.clear()
        forgetSessionData()
        // Both clear their local state before they first suspend: nothing waits on the network.
        viewModelScope.launch { api?.logout() }
        viewModelScope.launch { push.onLogout() }
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
        MinicamException.InvalidCredentials -> tr("Invalid email or password")
        MinicamException.Unauthorized -> {
            signOutLocally()
            tr("Your session expired. Sign in again.")
        }
        MinicamException.AccessRequired -> {
            store.set(CredentialKey.ACCESS_TOKEN, null)
            accessTokenPresent = false
            signOutLocally()
            tr("Sign in with Cloudflare Access again.")
        }
        MinicamException.RateLimited -> tr("Too many attempts. Wait a few minutes.")
        is MinicamException.Forbidden -> error.detail
        is MinicamException.Rejected -> error.detail
        MinicamException.NotFound -> tr("Not found or not allowed.")
        is MinicamException.Server -> tr("The server failed (%d).", error.code)
        else -> tr("Can't reach the server.")
    }

    private fun signOutLocally() {
        signedIn = false
        camerasLoaded = false
        selectedCamera = null
        screen = Screen.CAMERAS
        user = null
        forgetSessionData()
    }

    /** Nothing of this account outlives its session: update state, cached catalog and clips (ADR 0016). */
    private fun forgetSessionData() {
        updateJob?.cancel()
        systemUpdate = null
        updateProgress = null
        updateError = null
        modelCatalogCache.clear()
        PendingClips.clear()
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) { RecordingCache.clear(context) }
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
