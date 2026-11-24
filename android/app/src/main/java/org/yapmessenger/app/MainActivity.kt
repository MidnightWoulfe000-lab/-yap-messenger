package org.yapmessenger.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.text.TextWatcher
import android.text.Editable
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Yap's first real interface. Messages are still experimental, foreground-only
 * BLE transfers with no encryption, identity verification or saved history.
 */
class MainActivity : Activity() {
    private val ink = Color.rgb(23, 25, 35)
    private val surface = Color.rgb(34, 39, 56)
    private val muted = Color.rgb(164, 174, 196)
    private val lime = Color.rgb(199, 255, 60)
    private val cobalt = Color.rgb(49, 76, 255)
    private lateinit var bluetooth: YapBluetooth
    private lateinit var root: LinearLayout
    private var statusMessage = "Ready. Bluetooth is not running."
    private var pendingAction: (() -> Unit)? = null
    private var currentScreen = "chats"
    private var inputDraft = ""
    private var composerField: EditText? = null
    private val messages = mutableListOf<Pair<Boolean, String>>()
    private var lastOutgoing: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        window.decorView.systemUiVisibility = 0
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        bluetooth = YapBluetooth(this) { update ->
            runOnUiThread {
                statusMessage = update
                if (update.startsWith("Message received: ")) {
                    messages.add(false to update.removePrefix("Message received: "))
                }
                if (update == "Message delivered to the other phone.") {
                    lastOutgoing?.let { messages.add(true to it) }
                    lastOutgoing = null
                }
                if (currentScreen == "conversation" || currentScreen == "network" || currentScreen == "chats") {
                    if (currentScreen == "conversation") {
                        if (update.startsWith("Message received: ") ||
                            update == "Message delivered to the other phone.") {
                            if (composerField?.hasFocus() != true) render()
                        }
                    } else render()
                }
            }
        }
        render()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun rounded(colour: Int, radius: Int = 18, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(colour)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun label(text: String, size: Float, colour: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(colour)
            if (bold) setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun pad(view: View, left: Int = 18, top: Int = 12, right: Int = 18, bottom: Int = 12) {
        view.setPadding(dp(left), dp(top), dp(right), dp(bottom))
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun render() {
        root = column().apply { setBackgroundColor(ink) }
        if (currentScreen == "conversation") {
            root.addView(conversationHeader())
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
            }
            val messagesBody = column().apply { pad(this, 20, 18, 20, 18) }
            conversationMessages(messagesBody)
            scroll.addView(messagesBody)
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(composer())
        } else {
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
            }
            val body = column().apply { pad(this, 22, 20, 22, 20) }
            when (currentScreen) {
                "network" -> network(body)
                "settings" -> settings(body)
                else -> chats(body)
            }
            scroll.addView(body)
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(navigation())
            composerField = null
        }
        // Edge-to-edge Android versions may draw our header under the status bar
        // and our composer under the keyboard. Respect the real window insets.
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(0, bars.top, 0, maxOf(bars.bottom, keyboard.bottom))
            } else {
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun avatar(symbol: String, colour: Int, size: Int = 52): TextView =
        label(symbol, 23f, if (colour == lime) ink else Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(colour, size / 2)
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }

    private fun chats(body: LinearLayout) {
        val heading = row()
        heading.addView(label("yap", 42f, Color.WHITE, true))
        heading.addView(label(".", 42f, lime, true))
        heading.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        heading.addView(label("＋", 30f, lime).apply {
            minWidth = dp(48)
            gravity = Gravity.RIGHT
            setOnClickListener { currentScreen = "conversation"; render() }
        })
        body.addView(heading)
        body.addView(space(22))
        val tabs = row()
        listOf("All", "Groups", "Unread").forEachIndexed { index, title ->
            val active = index == 0
            tabs.addView(label(title, 14f, if (active) ink else muted, active).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                if (active) background = rounded(lime, 24)
                setOnClickListener {
                    if (!active) {
                        android.widget.Toast.makeText(
                            this@MainActivity, "Coming soon", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        body.addView(tabs)
        body.addView(space(23))
        val chat = row().apply {
            minimumHeight = dp(88)
            isClickable = true
            setOnClickListener { currentScreen = "conversation"; render() }
        }
        chat.addView(avatar("Y", cobalt))
        val details = column().apply { pad(this, 15, 5, 0, 5) }
        details.addView(label("Nearby Yap phone", 18f, Color.WHITE, true))
        details.addView(space(5))
        details.addView(label(
            if (messages.isEmpty()) "Tap to start a conversation" else messages.last().second,
            14f, muted
        ).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        details.addView(space(5))
        details.addView(label("Unverified · Bluetooth test", 12f, lime))
        chat.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(chat)
        body.addView(View(this).apply {
            setBackgroundColor(surface)
            layoutParams = LinearLayout.LayoutParams(-1, dp(1))
        })
        body.addView(space(32))
        body.addView(label("JUST GETTING STARTED", 12f, lime, true))
        body.addView(space(10))
        body.addView(label(
            "Real contacts and groups are on the way. For now, Yap can exchange short messages directly with another nearby phone.",
            15f, muted
        ))
    }

    private fun conversationHeader(): View {
        val header = row().apply { pad(this, 16, 12, 16, 14) }
        header.addView(label("‹", 38f, lime).apply {
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            setOnClickListener { currentScreen = "chats"; render() }
        })
        header.addView(avatar("Y", cobalt, 42))
        val identity = column().apply { pad(this, 12, 0, 0, 0) }
        identity.addView(label("Nearby Yap phone", 18f, Color.WHITE, true))
        identity.addView(label("Unverified Bluetooth peer", 12f, muted))
        header.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("⋮", 27f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            setOnClickListener { showBluetoothControls() }
        })
        return header
    }

    private fun conversationMessages(body: LinearLayout) {
        body.addView(label(
            "Experimental chat · Not encrypted · Don't share private information",
            12f, muted
        ).apply {
            gravity = Gravity.CENTER
            background = rounded(surface, 15)
            pad(this, 14, 11, 14, 11)
        })
        body.addView(space(22))
        if (messages.isEmpty()) {
            body.addView(space(60))
            body.addView(label("Say something.", 26f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
            })
            body.addView(space(8))
            body.addView(label(
                "Make one phone discoverable from the menu above, then send a message.",
                15f, muted
            ).apply { gravity = Gravity.CENTER })
        } else {
            messages.forEach { (mine, text) ->
                val wrapper = row().apply {
                    gravity = if (mine) Gravity.RIGHT else Gravity.LEFT
                }
                wrapper.addView(label(text, 16f, if (mine) ink else Color.WHITE).apply {
                    background = rounded(if (mine) lime else surface, 19)
                    pad(this, 17, 12, 17, 12)
                    maxWidth = (resources.displayMetrics.widthPixels * .76).toInt()
                })
                body.addView(wrapper)
                body.addView(space(13))
            }
        }
        body.addView(space(20))
        body.addView(label(statusMessage, 12f, muted).apply { gravity = Gravity.CENTER })
    }

    private fun composer(): View {
        val outer = column().apply { pad(this, 14, 8, 14, 16) }
        val line = row()
        val field = EditText(this).apply {
            hint = "Message (20 bytes)"
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine(true)
            background = rounded(surface, 24)
            pad(this, 18, 13, 18, 13)
            setText(inputDraft)
            setSelection(text.length)
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                inputDraft = s?.toString() ?: ""
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        composerField = field
        line.addView(field, LinearLayout.LayoutParams(0, dp(52), 1f))
        val send = label("➤", 23f, ink, true).apply {
            gravity = Gravity.CENTER
            background = rounded(lime, 26)
            minWidth = dp(52)
            minHeight = dp(52)
            setOnClickListener {
                val message = field.text.toString()
                inputDraft = message
                if (message.toByteArray(Charsets.UTF_8).size !in 1..20) {
                    android.widget.Toast.makeText(
                        this@MainActivity, "Use 1 to 20 UTF-8 bytes", android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    withBluetoothPermissions {
                        lastOutgoing = message
                        bluetooth.send(message)
                    }
                }
            }
        }
        val sendParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(9) }
        line.addView(send, sendParams)
        outer.addView(line)
        outer.addView(space(7))
        outer.addView(label("Bluetooth test · Tap ⋮ to make this phone discoverable", 11f, muted))
        return outer
    }

    private fun showBluetoothControls() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Bluetooth test")
            .setMessage("Status: $statusMessage\n\nMessages are not encrypted or saved. Keep both phones in Yap while testing.")
            .setPositiveButton("Make discoverable") { _, _ ->
                withBluetoothPermissions { bluetooth.startAdvertising() }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    

    private fun network(body: LinearLayout) {
        body.addView(label("Network", 34f, Color.WHITE, true))
        body.addView(space(8))
        body.addView(label("Your local mesh, as it develops.", 15f, muted))
        body.addView(space(26))
        body.addView(label("●  BLUETOOTH EXPERIMENT", 14f, lime, true))
        body.addView(space(14))
        body.addView(label(statusMessage, 17f, Color.WHITE).apply {
            background = rounded(surface)
            pad(this, 18, 22, 18, 22)
        })
        body.addView(space(22))
        body.addView(action("MAKE THIS PHONE DISCOVERABLE", lime, ink) {
            withBluetoothPermissions { bluetooth.startAdvertising() }
        })
        body.addView(space(22))
        body.addView(label(
            "Live peer maps, relay routes and verified identities are not available in this prototype. Yap currently sends short messages directly over Bluetooth.",
            15f, muted
        ))
    }

    private fun settings(body: LinearLayout) {
        body.addView(label("Settings", 34f, Color.WHITE, true))
        body.addView(space(20))
        body.addView(label("Yap · Android prototype", 18f, lime, true))
        body.addView(space(14))
        body.addView(label(
            "Messages are not encrypted, authenticated or stored. Bluetooth works only while the app is in the foreground. This is an early technical test, not a secure messenger.",
            16f, muted
        ))
        body.addView(space(26))
        body.addView(label("Communication is a right, not a privilege.", 17f, Color.WHITE, true))
    }

    private fun action(title: String, colour: Int, foreground: Int, callback: () -> Unit): Button =
        Button(this).apply {
            text = title
            textSize = 13f
            isAllCaps = false
            setTextColor(foreground)
            background = rounded(colour, 16)
            minHeight = dp(52)
            setOnClickListener { callback() }
        }

    private fun navigation(): View {
        val bar = row().apply {
            background = rounded(surface, 20)
            pad(this, 8, 7, 8, 7)
        }
        listOf("chats" to "▣\\nChats", "network" to "♧\\nNetwork", "settings" to "⚙\\nSettings")
            .forEach { (screen, title) ->
                val selected = currentScreen == screen
                val tab = label(title.replace("\\n", " "), 13f, if (selected) ink else Color.WHITE, selected).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(52)
                    if (selected) background = rounded(lime, 15)
                    setOnClickListener { currentScreen = screen; render() }
                }
                bar.addView(tab, LinearLayout.LayoutParams(0, dp(52), 1f))
            }
        return FrameLayout(this).apply {
            pad(this, 16, 4, 16, 12)
            addView(bar)
        }
    }

    private fun withBluetoothPermissions(action: () -> Unit) {
        val required = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            pendingAction = action
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 100) return
        val action = pendingAction
        pendingAction = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            action?.invoke()
        } else {
            statusMessage = "Bluetooth permissions are needed for this test."
            render()
        }
    }

    override fun onStop() {
        bluetooth.stop()
        super.onStop()
    }
}
