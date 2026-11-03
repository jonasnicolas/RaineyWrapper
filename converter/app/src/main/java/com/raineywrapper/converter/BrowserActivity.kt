package com.raineywrapper.converter

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Start screen: a file browser for picking a ROM. Also used, with [EXTRA_PICK_IMAGE],
 * to pick a cover image for the convert screen.
 */
class BrowserActivity : GamepadActivity() {

    companion object {
        const val EXTRA_PICK_IMAGE = "pick_image"
        const val EXTRA_START_DIR = "start_dir"
        const val RESULT_PATH = "path"
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
        private const val PREFS = "browser"
    }

    private class Entry(val file: File, val label: String, val isDir: Boolean)

    private val pickImage by lazy { intent.getBooleanExtra(EXTRA_PICK_IMAGE, false) }
    private lateinit var root: LinearLayout
    private lateinit var pathLabel: TextView
    private lateinit var list: ListView
    private var current: File? = null
    private var keyChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val parent = current?.parentFile
                if (parent != null && current !in storageRoots().map { it.second }) show(parent) else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!Environment.isExternalStorageManager()) showPermissionScreen() else showBrowser()
    }

    private fun showPermissionScreen() {
        root.removeAllViews()
        root.gravity = Gravity.CENTER
        root.addView(label("RaineyWrapper", 26f))
        root.addView(label(
            "\nTurn your ROMs into apps that launch straight into the game.\n\n" +
                "To find ROMs and cover images on this device, RaineyWrapper needs access to your files.",
            16f, Palette.SUBTLE,
        ).apply { gravity = Gravity.CENTER })
        val allow = button("Allow access to files", primary = true) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        root.addView(allow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { topMargin = dp(24) })
        defaultAction = allow
        allow.requestFocus()
    }

    private fun showBrowser() {
        if (::list.isInitialized && list.parent != null) {
            current?.let { show(it) } // refresh in case files changed
            return
        }
        root.removeAllViews()
        root.gravity = Gravity.NO_GRAVITY
        defaultAction = null

        root.addView(label(if (pickImage) "Choose a cover image" else "RaineyWrapper  ·  choose a game to convert", 20f))
        pathLabel = label("", 13f, Palette.SUBTLE).apply { setPadding(0, dp(4), 0, dp(8)) }
        root.addView(pathLabel)

        val roots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, dir) in storageRoots()) {
            roots.addView(button(name) { show(dir) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) })
        }
        if (pickImage) {
            roots.addView(button("No image (use title)") {
                setResult(RESULT_OK, Intent().putExtra(RESULT_PATH, ""))
                finish()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        }
        root.addView(HorizontalScrollView(this).apply { addView(roots) })

        list = ListView(this).apply {
            divider = null
            itemsCanFocus = false
            setSelector(android.R.color.transparent)
            setOnItemClickListener { _, _, position, _ ->
                val entry = adapter.getItem(position) as Entry
                if (entry.isDir) show(entry.file) else picked(entry.file)
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        val start = intent.getStringExtra(EXTRA_START_DIR)?.let(::File)?.takeIf { it.isDirectory }
            ?: getSharedPreferences(PREFS, MODE_PRIVATE).getString(prefKey(), null)?.let(::File)?.takeIf { it.isDirectory }
            ?: Environment.getExternalStorageDirectory()
        show(start)

        if (!pickImage && !keyChecked) {
            keyChecked = true
            lifecycleScope.launch {
                val origin = withContext(Dispatchers.IO) { runCatching { SigningKey.ensure(this@BrowserActivity) } }
                origin.onSuccess {
                    if (it == "imported") toast("Signing key imported from your computer. Games built on either side can update each other.")
                    if (it == "generated") toast("Created this device's signing key.")
                }.onFailure { toast("Signing key problem: ${it.message}") }
            }
        }
    }

    private fun prefKey() = if (pickImage) "last_image_dir" else "last_rom_dir"

    /** Internal storage plus any SD cards / USB drives. */
    private fun storageRoots(): List<Pair<String, File>> {
        val internal = Environment.getExternalStorageDirectory()
        val roots = mutableListOf("Internal storage" to internal)
        // Resolve aliases such as /storage/sdcard0 so each card is listed once, by its real path.
        val seen = mutableSetOf(internal.canonicalPath)
        File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead() }
            ?.map { it.canonicalFile }
            ?.filter { seen.add(it.path) }
            ?.sortedBy { it.path }
            ?.forEach { roots += "SD card (${it.name})" to it }
        return roots
    }

    private fun show(dir: File) {
        current = dir
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(prefKey(), dir.absolutePath).apply()
        pathLabel.text = dir.absolutePath

        val allowed = if (pickImage) IMAGE_EXTENSIONS else Systems.supportedExtensions(this)
        val children = dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }
        val dirs = children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        // Disc tracks (Game.bin next to Game.cue) are picked through their .cue, so don't list them.
        val cueStems = children.filter { it.ext == "cue" }.map { it.nameWithoutExtension.lowercase() }.toSet()
        val files = children
            .filter { it.isFile && it.ext in allowed }
            .filterNot { it.ext in setOf("bin", "img", "iso") && it.nameWithoutExtension.lowercase() in cueStems }
            .sortedBy { it.name.lowercase() }

        val entries = mutableListOf<Entry>()
        val parent = dir.parentFile
        if (parent != null && dir !in storageRoots().map { it.second }) entries += Entry(parent, "⬆   ..", true)
        dirs.forEach { entries += Entry(it, "📁   ${it.name}", true) }
        files.forEach { entries += Entry(it, (if (pickImage) "🖼   " else "🎮   ") + "${it.name}    ·  ${formatSize(it.length())}", false) }
        if (entries.isEmpty() || (entries.size == 1 && parent != null)) {
            entries += Entry(dir, if (pickImage) "(no images here)" else "(no ROMs here)", true)
        }

        list.adapter = EntryAdapter(this, entries)
        list.setSelection(0)
        list.requestFocus()
    }

    private fun picked(file: File) {
        if (pickImage) {
            setResult(RESULT_OK, Intent().putExtra(RESULT_PATH, file.absolutePath))
            finish()
        } else {
            startActivity(Intent(this, ConvertActivity::class.java).putExtra(ConvertActivity.EXTRA_ROM, file.absolutePath))
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private class EntryAdapter(context: Context, entries: List<Entry>) : ArrayAdapter<Entry>(context, 0, entries) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? TextView) ?: TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
                isSingleLine = true
                background = android.graphics.drawable.StateListDrawable().apply {
                    val sel = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Palette.ACCENT); cornerRadius = context.dp(8).toFloat()
                    }
                    addState(intArrayOf(android.R.attr.state_selected), sel)
                    addState(intArrayOf(android.R.attr.state_activated), sel)
                    addState(intArrayOf(android.R.attr.state_pressed), sel)
                    addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                }
            }
            view.text = getItem(position)!!.label
            return view
        }
    }
}
