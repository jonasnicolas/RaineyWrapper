package com.raineywrapper.player

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroView.GLRetroEvents
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GameActivity : ComponentActivity() {

    companion object {
        private const val TAG = "RaineyWrapper"
        private const val SRAM_AUTOSAVE_MS = 30_000L
        private const val PREFS = "player"
        private const val PREF_TOUCH = "touch_controls"
        private const val PREF_FILTER = "screen_filter"
        private const val PREF_RESOLUTION = "resolution_level"
        private const val PREF_WII_CONTROLLER = "wii_controller"
        private const val PREF_FPS = "fps_counter"
    }

    private lateinit var container: FrameLayout
    private lateinit var config: GameConfig
    private lateinit var saves: SaveStore
    private lateinit var menu: GameMenu
    private var retroView: GLRetroView? = null
    private var gamepad: GamepadInput? = null
    private var overlay: TouchOverlay? = null
    private var message: View? = null
    private var fpsLabel: TextView? = null
    private var starting = false

    /** True once the core has loaded the game; SRAM and states can't be touched before that. */
    private var gameRunning = false

    /** Resolution level the core was started with; a different saved level needs a restart. */
    private var startedResolution = 0

    /** Wii controller the core was started with; like resolution, a change needs a restart. */
    private var startedWiiController = WiiController.NUNCHUK

    /** Physical Start/Select currently held, for the Start+Select menu shortcut. */
    private val heldCombo = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferSixtyHz()
        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(container)
        enterImmersiveMode()

        config = GameConfig.load(this)
        saves = SaveStore(this)
        menu = GameMenu(this, config.title).apply { setItems(menuItems()) }
    }

    override fun onResume() {
        super.onResume()
        if (retroView == null && !starting) prepareAndStart()
    }

    private fun prepareAndStart() {
        if (config.linked && !Environment.isExternalStorageManager()) {
            showMessage(
                "${config.title}\n\nThis game plays its ROM from your device storage, so it needs " +
                    "permission to read files.",
                "Allow file access",
            ) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                )
            }
            return
        }
        if (config.external.isNotEmpty()) {
            launchExternal()
            return
        }
        starting = true
        lifecycleScope.launch {
            val loading = if (config.isExtracted(this@GameActivity)) null else showLoading()
            val rom = try {
                withContext(Dispatchers.IO) {
                    config.installSystemFiles(this@GameActivity, saves.systemDir)
                    config.installCoreData(this@GameActivity, saves.systemDir)
                    var lastPercent = -1
                    config.extractRom(this@GameActivity) { progress ->
                        val percent = (progress * 100).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            loading?.post { loading.text = loadingText(percent) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare game files", e)
                loading?.let { container.removeView(it) }
                showMessage(e.message ?: "Failed to prepare game files", "Quit") { finishAndRemoveTask() }
                starting = false
                return@launch
            }
            loading?.let { container.removeView(it) }
            clearMessage()
            val sram = withContext(Dispatchers.IO) { saves.readSram() }
            startEmulator(rom, sram)
        }
    }

    private fun startEmulator(rom: File, sram: ByteArray?) {
        val data = GLRetroViewData(this).apply {
            coreFilePath = config.core
            gameFilePath = rom.absolutePath
            systemDirectory = saves.systemDir.absolutePath
            savesDirectory = saves.savesDir.absolutePath
            saveRAMState = sram
            shader = screenFilter().config
            preferLowLatencyAudio = true
            rumbleEventsEnabled = false
            startedResolution = resolutionLevel()
            startedWiiController = wiiController()
            variables = coreVariables(config.system) +
                listOfNotNull(InternalResolution.levels(config.system).getOrNull(startedResolution)?.variable)
        }

        val view = GLRetroView(this, data)
        retroView = view
        gamepad = GamepadInput(view, config.system, startedWiiController)
        lifecycle.addObserver(view)
        // Registered after the view's own observers, so on resume this runs after
        // LibretroDroid restarts emulation and can re-pause it if the menu is open.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && menu.isOpen) pauseEmulation()
        })
        container.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )

        overlay = TouchOverlay(this, TouchOverlay.layoutFor(config.system, startedWiiController),
            onKey = { action, key -> view.sendKeyEvent(action, key, 0) },
            onAnalog = { right, x, y ->
                val source = if (right) GLRetroView.MOTION_SOURCE_ANALOG_RIGHT else GLRetroView.MOTION_SOURCE_ANALOG_LEFT
                view.sendMotionEvent(source, x, y, 0)
            },
            onMenu = { openMenu() },
        ).also {
            it.visibility = if (touchControlsEnabled()) View.VISIBLE else View.GONE
            container.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        // FPS counter: top middle, just below the touch controls' menu button (which ends at 0.9 of
        // TouchOverlay's size unit, a tenth of the short side).
        fpsLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            textSize = 14f
            visibility = if (fpsCounterEnabled()) View.VISIBLE else View.GONE
        }.also {
            val top = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 13 / 100
            container.addView(it, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = top })
        }
        container.addView(menu, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        lifecycleScope.launch {
            view.getGLRetroEvents().first { it is GLRetroEvents.FrameRendered }
            gameRunning = true
        }

        lifecycleScope.launch {
            view.getGLRetroErrors().collect { code ->
                Log.e(TAG, "Emulator error $code")
                Toast.makeText(this@GameActivity, "Emulator error ($code)", Toast.LENGTH_LONG).show()
                finish()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    fpsLabel?.takeIf { it.visibility == View.VISIBLE && gameRunning }?.let {
                        it.text = String.format(java.util.Locale.US, "%.1f FPS", view.getFps())
                    }
                    delay(1_000)
                }
            }
        }

        // Periodic SRAM flush so a crash or force-stop doesn't lose in-game saves.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(SRAM_AUTOSAVE_MS)
                    if (!gameRunning || menu.isOpen) continue
                    val bytes = view.serializeSRAM()
                    withContext(Dispatchers.IO) { saves.writeSram(bytes) }
                }
            }
        }
    }

    /** Core options that differ from the core's defaults. */
    private fun coreVariables(system: String): Array<Variable> = when (system) {
        "gamecube" -> dolphinVariables()
        "wii" -> dolphinVariables() + arrayOf(
            Variable("dolphin_wiimote_default_device", startedWiiController.device),
            // The right stick moves the Wii pointer and it stays put when released (the default snaps
            // back to the centre), so you can aim, let go and press A with the same thumb.
            Variable("dolphin_ir_mode", "0"),
        )
        "ps2" -> arrayOf(
            // Vulkan: far lower per-draw CPU cost than GL ES on mobile GPUs (GL ES was driver-bound).
            Variable("pcsx2_renderer", "Vulkan"),
            // Mali has no dual-source blending, which PCSX2's Vulkan blending otherwise assumes;
            // maximum accuracy does those blends in the shader via framebuffer fetch instead.
            Variable("pcsx2_blending_accuracy", "Maximum"),
            // The emulated PS2 CPU (EE) is then the bottleneck; most games don't use its full budget,
            // so emulate fewer cycles (the usual Android speed hack).
            Variable("pcsx2_ee_cycle_rate", "60% (Underclock)"),
            Variable("pcsx2_ee_cycle_skip", "Mild Underclock"),
            Variable("pcsx2_ee_accurate_fpu", "disabled"),
        )
        else -> emptyArray()
    }

    private fun dolphinVariables() = arrayOf(
        // Compile shaders in the background and skip drawing until they're ready, instead of
        // freezing the game each time a new effect appears (Dolphin's main source of stutter).
        Variable("dolphin_shader_compilation_mode", "3"),
        // Dolphin logs every disc read at its default level; errors only.
        Variable("dolphin_log_level", "2"),
        // No "Video Info" banner and other emulator messages over the game.
        Variable("dolphin_osd_enabled", "disabled"),
    )

    override fun onPause() {
        // The emulation thread is already paused at this point (lifecycle observers
        // see ON_PAUSE first), so read SRAM directly instead of queueing onto it.
        if (gameRunning) retroView?.let { saves.writeSram(it.serializeSRAM(false)) }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    /**
     * Systems without a bundled core (PS2, Dreamcast) open the game in an installed emulator app.
     * Single-file games are shared through this app's FileProvider, so the emulator needs no
     * permission of its own. Multi-file discs (.gdi/.cue) need their sibling tracks, which a
     * single shared file can't provide, so they're passed as a storage document URI the way
     * frontends like ES-DE do; the emulator must already have access to that folder.
     */
    private fun launchExternal() {
        val file = File(config.rom)
        if (!file.exists()) {
            showMessage("Game file not found:\n${config.rom}\n\nIt may have been moved, renamed or deleted, or its SD card removed.", "Quit") {
                finishAndRemoveTask()
            }
            return
        }
        val emulator = config.external.firstOrNull { isInstalled(it.packageName) }
        if (emulator == null) {
            val names = config.external.map { it.name }.distinct().joinToString(" or ")
            showMessage("${config.title}\n\nThis game needs $names installed to play.", "Quit") { finishAndRemoveTask() }
            return
        }
        val intent = Intent(emulator.action)
            .setComponent(ComponentName(emulator.packageName, emulator.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (emulator.passPath && emulator.extra != null) {
            intent.putExtra(emulator.extra, file.absolutePath)
        } else {
            val multiFile = file.extension.lowercase() in setOf("gdi", "cue", "m3u")
            val uri = if (multiFile) storageDocumentUri(file) else FileProvider.getUriForFile(this, "$packageName.files", file)
            if (!multiFile) {
                grantUriPermission(emulator.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (emulator.extra != null) {
                intent.putExtra(emulator.extra, uri.toString())
                intent.clipData = ClipData.newRawUri(file.name, uri) // carries the read grant with the extra
            } else {
                intent.data = uri
            }
        }
        try {
            startActivity(intent)
            finishAndRemoveTask()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't start ${emulator.name}", e)
            showMessage("Couldn't open ${emulator.name}: ${e.message}", "Quit") { finishAndRemoveTask() }
        }
    }

    /** /storage/emulated/0/a/b.gdi -> content://com.android.externalstorage.documents/document/primary%3Aa%2Fb.gdi */
    private fun storageDocumentUri(file: File): Uri {
        val path = file.canonicalPath
        val internal = Environment.getExternalStorageDirectory().canonicalPath
        val docId = if (path.startsWith("$internal/")) {
            "primary:" + path.removePrefix("$internal/")
        } else {
            val parts = path.removePrefix("/storage/").split("/", limit = 2) // [volume id, relative path]
            parts[0] + ":" + parts.getOrElse(1) { "" }
        }
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    // --- menu ---------------------------------------------------------------

    private fun menuItems() = listOfNotNull(
        GameMenu.Item({ "Resume" }) { closeMenu() },
        GameMenu.Item({ "Save state" }, { gameRunning }) { saveState() },
        GameMenu.Item({ "Load state" }, { gameRunning && saves.hasState() }) { loadState() },
        GameMenu.Item({ "Reset game" }, { gameRunning }) {
            retroView?.reset(false)
            closeMenu()
        },
        GameMenu.Item({ "Screen filter: " + screenFilter().label }) {
            val next = ScreenFilter.entries[(screenFilter().ordinal + 1) % ScreenFilter.entries.size]
            prefs().edit().putString(PREF_FILTER, next.name).apply()
            retroView?.shader = next.config
            menu.refresh()
        },
        GameMenu.Item(
            { "Resolution: " + resolutionLabel() },
            { InternalResolution.levels(config.system).isNotEmpty() },
        ) {
            val count = InternalResolution.levels(config.system).size
            prefs().edit().putInt(PREF_RESOLUTION, (resolutionLevel() + 1) % count).apply()
            menu.refresh()
        },
        GameMenu.Item({ "Wii controller: " + wiiController().label }) {
            val next = WiiController.entries[(wiiController().ordinal + 1) % WiiController.entries.size]
            prefs().edit().putString(PREF_WII_CONTROLLER, next.name).apply()
            menu.refresh()
        }.takeIf { config.system == "wii" },
        GameMenu.Item(
            {
                when {
                    resolutionLevel() != startedResolution -> "Restart game to apply resolution"
                    wiiController() != startedWiiController -> "Restart game to apply controller"
                    else -> "Restart game"
                }
            },
            { gameRunning },
        ) { restartGame() },
        GameMenu.Item({ "FPS counter: " + if (fpsCounterEnabled()) "On" else "Off" }) {
            val enabled = !fpsCounterEnabled()
            prefs().edit().putBoolean(PREF_FPS, enabled).apply()
            fpsLabel?.visibility = if (enabled) View.VISIBLE else View.GONE
            menu.refresh()
        },
        GameMenu.Item({ "Touch controls: " + if (touchControlsEnabled()) "On" else "Off" }) {
            setTouchControls(!touchControlsEnabled())
            menu.refresh()
        },
        GameMenu.Item({ "Quit" }) { finishAndRemoveTask() },
    )

    // --- graphics -------------------------------------------------------------

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun fpsCounterEnabled() = prefs().getBoolean(PREF_FPS, false)

    private fun screenFilter(): ScreenFilter =
        prefs().getString(PREF_FILTER, null)?.let { name -> ScreenFilter.entries.firstOrNull { it.name == name } } ?: ScreenFilter.SHARP

    private fun wiiController(): WiiController =
        prefs().getString(PREF_WII_CONTROLLER, null)?.let { name -> WiiController.entries.firstOrNull { it.name == name } }
            ?: WiiController.NUNCHUK

    private fun resolutionLevel(): Int {
        val count = InternalResolution.levels(config.system).size
        return prefs().getInt(PREF_RESOLUTION, 0).coerceIn(0, maxOf(count - 1, 0))
    }

    private fun resolutionLabel(): String {
        val levels = InternalResolution.levels(config.system)
        if (levels.isEmpty()) return "not available"
        val label = levels[resolutionLevel()].label
        return if (resolutionLevel() > 0) "$label (may slow down)" else label
    }

    /**
     * Cores read resolution options only at startup, and some (Vulkan PCSX2) can't be reloaded in
     * the same process, so restart the app in a fresh process after saving in-game progress.
     */
    private fun restartGame() {
        if (gameRunning) retroView?.let { saves.writeSram(it.serializeSRAM(false)) }
        startActivity(
            Intent(this, GameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun openMenu() {
        if (menu.isOpen || retroView == null) return
        overlay?.releaseAll()
        heldCombo.clear()
        pauseEmulation()
        menu.open()
    }

    private fun closeMenu() {
        if (!menu.isOpen) return
        menu.close()
        resumeEmulation()
    }

    private fun pauseEmulation() {
        retroView?.apply {
            audioEnabled = false
            onPause()
        }
    }

    private fun resumeEmulation() {
        retroView?.apply {
            onResume()
            audioEnabled = true
        }
    }

    // While the menu is open the GL thread is paused, so states are read and
    // written directly rather than queued onto the emulation thread.
    private fun saveState() {
        val view = retroView ?: return
        val ok = runCatching { saves.writeState(view.serializeState(false)) }.getOrDefault(false)
        toast(if (ok) "State saved" else "Couldn't save state")
        closeMenu()
    }

    private fun loadState() {
        val view = retroView ?: return
        val data = saves.readState()
        val ok = data != null && runCatching { view.unserializeState(data, false) }.getOrDefault(false)
        toast(if (ok) "State loaded" else "Couldn't load state")
        closeMenu()
    }

    /**
     * Many screens switch between 60, 90 and 120 Hz. Games are made for 60 (or 50), and the emulator
     * paces frames by the refresh rate it sees at startup, so ask for the 60 Hz mode while playing:
     * steady pacing, no speed-up when the screen changes mode, and less battery.
     */
    @Suppress("DEPRECATION")
    private fun preferSixtyHz() {
        val display = windowManager.defaultDisplay
        val current = display.mode
        val mode = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minByOrNull { kotlin.math.abs(it.refreshRate - 60f) } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
    }

    // --- touch controls -----------------------------------------------------

    private fun touchControlsEnabled(): Boolean {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        return if (prefs.contains(PREF_TOUCH)) prefs.getBoolean(PREF_TOUCH, true) else !hasPhysicalGamepad()
    }

    private fun setTouchControls(enabled: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_TOUCH, enabled).apply()
        overlay?.releaseAll()
        overlay?.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun hasPhysicalGamepad() = InputDevice.getDeviceIds().any { id ->
        val sources = InputDevice.getDevice(id)?.sources ?: 0
        sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    // --- input --------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (message != null) return super.dispatchKeyEvent(translateForUi(event))
        if (menu.isOpen) return handleMenuKey(event)

        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_UP) openMenu()
            return true
        }
        if (code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (event.action == KeyEvent.ACTION_DOWN) heldCombo += code else heldCombo -= code
            if (heldCombo.size == 2) {
                retroView?.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_START, 0)
                retroView?.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_SELECT, 0)
                openMenu()
                return true
            }
        }
        if (gamepad?.onKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleMenuKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) menu.clickFocused()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_MODE -> {
                if (event.action == KeyEvent.ACTION_UP) closeMenu()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT -> return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Lets gamepad A press the focused on-screen button outside the game. */
    private fun translateForUi(event: KeyEvent): KeyEvent =
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) KeyEvent(event.action, KeyEvent.KEYCODE_DPAD_CENTER) else event

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // With the menu open, let Android turn stick movement into d-pad focus moves.
        if (!menu.isOpen && gamepad?.onMotionEvent(event) == true) return true
        return super.dispatchGenericMotionEvent(event)
    }

    // --- screens shown instead of the game ----------------------------------

    /** Shown on first launch while large games are copied out of the APK. */
    private fun showLoading(): TextView {
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            text = loadingText(0)
        }
        container.addView(
            text,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        return text
    }

    private fun loadingText(percent: Int) = "${config.title}\n\nPreparing game for first launch… $percent%"

    private fun showMessage(text: String, buttonLabel: String, onClick: () -> Unit) {
        clearMessage()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            addView(TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                this.text = text
            })
            addView(Button(context).apply {
                this.text = buttonLabel
                isAllCaps = false
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 48
            })
        }
        container.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        message = box
        box.getChildAt(1).requestFocus()
    }

    private fun clearMessage() {
        message?.let { container.removeView(it) }
        message = null
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
