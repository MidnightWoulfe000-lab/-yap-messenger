package org.yapmessenger.app

internal object NativeBridge {
    init { System.loadLibrary("yap_android") }
    @JvmStatic external fun execute(request: String, now: Long): String
}
