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
    private val messages = mutableListOf<Pair<Boolean, String>>()
    private var lastOutgoing: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        window.decorView.systemUiVisibility = 0
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
                if (currentScreen == "conversation" || currentScreen == "network") render()
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
        val content = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val body = column().apply { pad(this, 22, 24, 22, 16) }
        when (currentScreen) {
            "conversation" -> conversation(body)
            "network" -> network(body)
            "settings" -> settings(body)
            else -> chats(body)
        }
        content.addView(body)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        if (currentScreen != "conversation") root.addView(navigation())
        setContentView(root)
    }

    private fun chats(body: LinearLayout) {
        val heading = row()
        heading.addView(label("yap", 40f, Color.WHITE, true))
        heading.addView(label(".", 42f, lime, true))
        body.addView(heading)
        body.addView(space(5))
        body.addView(label("Nobody gets to silence you.", 14f, muted))
        body.addView(space(30))
        body.addView(label("CHATS", 12f, lime, true))
        body.addView(space(13))

        val chat = column().apply {
            background = rounded(surface, 22)
            pad(this, 18, 20, 18, 20)
            isClickable = true
            setOnClickListener { currentScreen = "conversation"; render() }
        }
        chat.addView(label("●  Nearby Yap phone", 19f, Color.WHITE, true))
        chat.addView(space(8))
        chat.addView(label(
            if (messages.isEmpty()) "Start a Bluetooth conversation" else messages.last().second,
            14f, muted
        ))
        chat.addView(space(10))
        chat.addView(label("Experimental · No verified identity", 12f, lime))
        body.addView(chat)
        body.addView(space(22))
        body.addView(label("EARLY ACCESS", 12f, muted, true))
        body.addView(space(10))
        body.addView(label(
            "We're building Yap in the open. Contacts, groups and saved chats are coming later. For now, messages travel directly between nearby phones.",
            15f, muted
        ))
        body.addView(space(20))
        val open = action("OPEN BLUETOOTH CHAT", lime, ink) {
            currentScreen = "conversation"; render()
        }
        body.addView(open)
    }

    private fun conversation(body: LinearLayout) {
        val top = row()
        val back = label("‹", 36f, lime, true).apply {
            minWidth = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { currentScreen = "chats"; render() }
        }
        top.addView(back)
        val identity = column()
        identity.addView(label("Nearby Yap phone", 20f, Color.WHITE, true))
        identity.addView(label("Unverified Bluetooth peer", 12f, muted))
        top.addView(identity)
        body.addView(top)
        body.addView(space(22))
        body.addView(label("LOCAL BLUETOOTH TEST", 12f, lime, true))
        body.addView(space(10))
        val warning = label(
            "Not encrypted. Anyone using this test should avoid sharing private information.",
            13f, muted
        ).apply { background = rounded(surface); pad(this, 15, 13, 15, 13) }
        body.addView(warning)
        body.addView(space(18))

        if (messages.isEmpty()) {
            body.addView(space(45))
            body.addView(label("Your conversation starts here.", 24f, Color.WHITE, true))
            body.addView(space(10))
            body.addView(label(
                "Make one phone discoverable, then send a short message from the other.",
                15f, muted
            ))
            body.addView(space(35))
        } else {
            messages.forEach { (mine, text) ->
                val wrapper = row().apply {
                    gravity = if (mine) Gravity.RIGHT else Gravity.LEFT
                }
                val bubble = label(text, 16f, if (mine) ink else Color.WHITE).apply {
                    background = rounded(if (mine) lime else surface, 18)
                    pad(this, 16, 13, 16, 13)
                    maxWidth = (resources.displayMetrics.widthPixels * .76).toInt()
                }
                wrapper.addView(bubble)
                body.addView(wrapper)
                body.addView(space(10))
            }
            body.addView(label("Messages are not saved after closing the app.", 12f, muted))
            body.addView(space(20))
        }

        body.addView(label("BLUETOOTH STATUS", 12f, lime, true))
        body.addView(space(7))
        body.addView(label(statusMessage, 14f, muted))
        body.addView(space(15))
        body.addView(action("MAKE THIS PHONE DISCOVERABLE", surface, Color.WHITE) {
            withBluetoothPermissions { bluetooth.startAdvertising() }
        })
        body.addView(space(18))
        val field = EditText(this).apply {
            hint = "Message (20 bytes max)"
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine(true)
            background = rounded(surface)
            pad(this, 16, 12, 16, 12)
            setText(inputDraft)
            setSelection(text.length)
        }
        body.addView(field)
        body.addView(space(10))
        body.addView(action("SEND TO NEARBY PHONE  ➜", lime, ink) {
            val message = field.text.toString()
            inputDraft = message
            if (message.toByteArray(Charsets.UTF_8).size !in 1..20) {
                statusMessage = "Messages must be between 1 and 20 UTF-8 bytes."
                render()
            } else {
                withBluetoothPermissions {
                    lastOutgoing = message
                    bluetooth.send(message)
                }
            }
        })
        body.addView(space(18))
        body.addView(action("←  BACK TO CHATS", surface, Color.WHITE) {
            inputDraft = field.text.toString()
            currentScreen = "chats"; render()
        })
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
        listOf("chats" to "Chats", "network" to "Network", "settings" to "Settings")
            .forEach { (screen, title) ->
                val selected = currentScreen == screen
                val tab = label(title, 14f, if (selected) ink else Color.WHITE, selected).apply {
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
