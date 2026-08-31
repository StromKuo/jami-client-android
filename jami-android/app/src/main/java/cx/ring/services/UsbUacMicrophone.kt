/*
 * Copyright (C) 2004-2026 Savoir-faire Linux Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package cx.ring.services

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import net.jami.daemon.JamiService

/**
 * Application-owned UAC capture for devices whose audio interface is visible
 * in USB descriptors but is not exposed by the Android TV audio policy.
 */
internal class UsbUacMicrophone(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var started = false
    private var captureRequested = false
    private var permissionPending = false

    private val permissionIntent: PendingIntent by lazy {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    permissionPending = false
                    val device = getDevice(intent) ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(TAG, "USB permission granted for ${describe(device)}")
                        if (captureRequested)
                            openAndStart(device)
                        else
                            Log.i(TAG, "USB permission granted during startup preflight; capture will start with the next call")
                    } else {
                        Log.e(TAG, "USB permission denied for ${describe(device)}")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getDevice(intent)
                    if (device?.vendorId == STREAMCAM_VENDOR_ID && device.productId == STREAMCAM_PRODUCT_ID) {
                        Log.w(TAG, "StreamCam detached")
                        stop()
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            appContext.registerReceiver(receiver, filter)
    }

    @Synchronized
    fun start() {
        captureRequested = true
        Log.i(TAG, "start requested started=$started permissionPending=$permissionPending")
        if (started) {
            val nativeActive = runCatching { JamiService.isUsbUacCaptureActive() }
                .onFailure { Log.w(TAG, "Unable to query native USB UAC state", it) }
                .getOrDefault(false)
            if (nativeActive) {
                Log.i(TAG, "USB UAC capture already active (native capture is running)")
                return
            }
            Log.w(TAG, "USB UAC local state was stale; restarting native capture")
            stop()
            captureRequested = true
        }
        val device = findStreamCam()
        if (device == null) {
            Log.i(TAG, "No Logitech StreamCam ($STREAMCAM_VENDOR_ID:$STREAMCAM_PRODUCT_ID) found")
            return
        }
        Log.i(TAG, "Found ${describe(device)}")
        val hasPermission = usbManager.hasPermission(device)
        Log.i(TAG, "USB permission state device=${describe(device)} granted=$hasPermission interfaces=${device.interfaceCount}")
        if (!hasPermission) {
            requestPermission(device)
            return
        }
        openAndStart(device)
    }

    /**
     * Request USB host permission from a foreground Activity. Android TV does
     * not list this permission in the app settings page; SystemUI displays a
     * one-time device authorization dialog instead.
     */
    @Synchronized
    fun requestPermission() {
        requestPermission(findStreamCam())
    }

    private fun requestPermission(device: UsbDevice?) {
        if (device == null) {
            Log.i(TAG, "No Logitech StreamCam ($STREAMCAM_VENDOR_ID:$STREAMCAM_PRODUCT_ID) found for USB permission preflight")
            return
        }
        Log.i(TAG, "USB permission preflight found ${describe(device)}")
        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "USB permission already granted for ${describe(device)}")
            permissionPending = false
            return
        }
        if (permissionPending) {
            Log.i(TAG, "USB permission request already pending for ${describe(device)}")
            return
        }
        permissionPending = true
        Log.w(TAG, "Requesting USB permission from foreground activity for ${describe(device)}")
        usbManager.requestPermission(device, permissionIntent)
    }

    @Synchronized
    fun stop() {
        Log.i(TAG, "stop requested started=$started native=${runCatching { JamiService.isUsbUacCaptureActive() }.getOrDefault(false)}")
        captureRequested = false
        permissionPending = false
        if (started) {
            runCatching { JamiService.stopUsbUacCapture() }
                .onFailure { Log.w(TAG, "Stopping native USB UAC capture failed", it) }
            started = false
        }
        val localConnection = connection
        val localInterface = claimedInterface
        if (localConnection != null && localInterface != null)
            runCatching { localConnection.releaseInterface(localInterface) }
        localConnection?.close()
        connection = null
        claimedInterface = null
    }

    @Synchronized
    private fun openAndStart(device: UsbDevice) {
        if (started) return
        val audioInterface = findAudioStreamingInterface(device)
        if (audioInterface == null) {
            Log.e(TAG, "No StreamCam USB AudioStreaming interface with a usable IN endpoint")
            return
        }
        val endpoint = findIsochronousInput(audioInterface)
        if (endpoint == null) {
            Log.e(TAG, "No isochronous audio IN endpoint on interface ${audioInterface.id} alt=${audioInterface.alternateSetting}")
            return
        }

        val localConnection = usbManager.openDevice(device)
        if (localConnection == null) {
            Log.e(TAG, "UsbManager.openDevice failed for ${describe(device)}")
            return
        }
        Log.i(TAG, "USB device opened fd=${localConnection.fileDescriptor} device=${describe(device)}")
        if (!localConnection.claimInterface(audioInterface, true)) {
            Log.e(TAG, "Unable to claim USB audio interface ${audioInterface.id}")
            localConnection.close()
            return
        }
        Log.i(TAG, "USB interface claimed id=${audioInterface.id} alt=${audioInterface.alternateSetting}")
        if (!localConnection.setInterface(audioInterface)) {
            Log.e(TAG, "Unable to select USB audio interface ${audioInterface.id} alt=${audioInterface.alternateSetting}")
            localConnection.releaseInterface(audioInterface)
            localConnection.close()
            return
        }

        val fd = localConnection.fileDescriptor
        if (fd < 0) {
            Log.e(TAG, "USB device returned invalid file descriptor")
            localConnection.releaseInterface(audioInterface)
            localConnection.close()
            return
        }

        Log.i(TAG, "Starting native USB UAC: fd=$fd interface=${audioInterface.id} alt=${audioInterface.alternateSetting} endpoint=0x${endpoint.address.toString(16)} direction=${endpoint.direction} type=${endpoint.type} packet=${endpoint.maxPacketSize}")
        val nativeStarted = runCatching {
            JamiService.startUsbUacCapture(
                fd,
                audioInterface.id,
                audioInterface.alternateSetting,
                endpoint.address,
                endpoint.maxPacketSize
            )
        }.onFailure { Log.e(TAG, "Starting native USB UAC capture failed", it) }.getOrDefault(false)
        if (!nativeStarted) {
            Log.e(TAG, "Native USB UAC capture was not started")
            localConnection.releaseInterface(audioInterface)
            localConnection.close()
            return
        }

        connection = localConnection
        claimedInterface = audioInterface
        started = true
        val nativeActive = runCatching { JamiService.isUsbUacCaptureActive() }
            .onFailure { Log.w(TAG, "Unable to query native USB UAC state after start", it) }
            .getOrDefault(false)
        Log.i(TAG, "Native USB UAC capture started: StreamCam 48 kHz, 2 channels, 16-bit PCM active=$nativeActive")
    }

    private fun findStreamCam(): UsbDevice? = usbManager.deviceList.values.firstOrNull {
        it.vendorId == STREAMCAM_VENDOR_ID && it.productId == STREAMCAM_PRODUCT_ID
    }

    private fun findAudioStreamingInterface(device: UsbDevice): UsbInterface? {
        var selected: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            val endpointSummary = (0 until usbInterface.endpointCount).joinToString { endpointIndex ->
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                "0x${endpoint.address.toString(16)}/type=${endpoint.type}/max=${endpoint.maxPacketSize}"
            }
            Log.i(TAG, "USB interface id=${usbInterface.id} alt=${usbInterface.alternateSetting} class=${usbInterface.interfaceClass} subclass=${usbInterface.interfaceSubclass} endpoints=[$endpointSummary]")
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                usbInterface.interfaceSubclass == AUDIO_SUBCLASS_STREAMING &&
                usbInterface.alternateSetting == STREAMCAM_AUDIO_ALT_SETTING &&
                findIsochronousInput(usbInterface) != null
            ) {
                selected = usbInterface
            }
        }
        return selected
    }

    private fun findIsochronousInput(usbInterface: UsbInterface): UsbEndpoint? =
        (0 until usbInterface.endpointCount)
            .map { usbInterface.getEndpoint(it) }
            .firstOrNull { endpoint ->
                endpoint.direction == UsbConstants.USB_DIR_IN && endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
            }

    @Suppress("DEPRECATION")
    private fun getDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun describe(device: UsbDevice): String =
        "${device.deviceName} ${device.vendorId.toString(16)}:${device.productId.toString(16)} ${device.productName ?: ""}"

    companion object {
        private const val TAG = "UsbUacMicrophone"
        private const val ACTION_USB_PERMISSION = "cx.ring.action.USB_PERMISSION"
        private const val STREAMCAM_VENDOR_ID = 0x046d
        private const val STREAMCAM_PRODUCT_ID = 0x0893
        private const val STREAMCAM_AUDIO_ALT_SETTING = 4
        private const val AUDIO_SUBCLASS_STREAMING = 2
    }
}
