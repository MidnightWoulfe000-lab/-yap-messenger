@file:Suppress("DEPRECATION")

package org.yapmessenger.app

import android.annotation.SuppressLint
import android.graphics.Rect
import android.hardware.Camera
import android.view.MotionEvent
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.camera.CameraParametersCallback

/**
 * The QR scanner, locked upright, with tap to focus: touching the preview points the camera's
 * focus and exposure at that spot and refocuses straight away.
 */
class PortraitCaptureActivity : CaptureActivity() {
    @SuppressLint("ClickableViewAccessibility")
    override fun initializeContent(): DecoratedBarcodeView {
        val view = super.initializeContent()
        val preview = view.barcodeView
        preview.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP && v.width > 0 && v.height > 0) {
                focusAt(preview, event.x / v.width, event.y / v.height)
                v.performClick()
            }
            true
        }
        return view
    }

    /** [u] and [v] are the tap's position across and down the preview, 0 to 1. */
    private fun focusAt(preview: com.journeyapps.barcodescanner.BarcodeView, u: Float, v: Float) {
        // Camera focus areas run -1000..1000 on the sensor, which sits rotated 90 degrees to an upright phone.
        val cx = (v * 2000 - 1000).toInt()
        val cy = ((1 - u) * 2000 - 1000).toInt()
        val half = 150
        val area = Camera.Area(Rect((cx - half).coerceIn(-1000, 1000), (cy - half).coerceIn(-1000, 1000),
            (cx + half).coerceIn(-1000, 1000), (cy + half).coerceIn(-1000, 1000)), 1000)
        preview.cameraInstance?.changeCameraParameters(CameraParametersCallback { p ->
            if (p.maxNumFocusAreas > 0) p.focusAreas = listOf(area)
            if (p.maxNumMeteringAreas > 0) p.meteringAreas = listOf(area)
            p
        })
        // The library restarts its autofocus whenever the torch is set, which refocuses at once.
        preview.setTorch(false)
    }
}
