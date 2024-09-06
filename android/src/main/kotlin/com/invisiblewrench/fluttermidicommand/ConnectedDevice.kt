package com.invisiblewrench.fluttermidicommand

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiOutputPort
import android.os.Handler
import io.flutter.plugin.common.MethodChannel.Result
import android.media.midi.MidiReceiver
import java.nio.ByteBuffer

class ConnectedDevice(
    private val midiDevice: MidiDevice,
    private val setupStreamHandler: FMCStreamHandler?,
    context: Context
) {
    private var inputPort: MidiInputPort? = null
    private var outputPort: MidiOutputPort? = null
    private var usbDeviceConnection: UsbDeviceConnection? = null
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun connectWithStreamHandler(streamHandler: FMCStreamHandler, connectResult: Result?) {
        val deviceInfo = midiDevice.info
        val usbDevice = findUsbDevice(deviceInfo)

        if (usbDevice != null) {
            // USB MIDI device connection
            usbDeviceConnection = usbManager.openDevice(usbDevice)
            // Implement your USB connection handling here
        } else {
            // Non-USB MIDI device
            setupNonUsbDevice()
        }

        // Delay result callback to ensure device is connected
        Handler().postDelayed({
            connectResult?.success(null)
            setupStreamHandler?.send("deviceConnected")
        }, 2500)
    }

    private fun setupNonUsbDevice() {
        val deviceInfo = midiDevice.info
        if (deviceInfo.inputPortCount > 0) {
            inputPort = midiDevice.openInputPort(0)
        }
        if (deviceInfo.outputPortCount > 0) {
            outputPort = midiDevice.openOutputPort(0)
            outputPort?.connect(midiReceiver)
        }
    }

    private fun findUsbDevice(deviceInfo: MidiDeviceInfo?): UsbDevice? {
        deviceInfo?.let {
            val usbDevices = usbManager.deviceList
            for (device in usbDevices.values) {
                // Match device based on vendorId or other criteria
                if (device.vendorId == deviceInfo.properties.getInt("vendorId", -1)) {
                    return device
                }
            }
        }
        return null
    }

    fun send(data: ByteArray, timestamp: Long?) {
        if (usbDeviceConnection != null) {
            // Handle USB-MIDI sending here
        } else {
            outputPort?.send(data, 0, data.size)
        }
    }

    fun close() {
        inputPort?.flush()
        inputPort?.close()
        outputPort?.close()
        usbDeviceConnection?.close()
        setupStreamHandler?.send("deviceDisconnected")
    }

    private val midiReceiver = object : MidiReceiver() {
        override fun onSend(msg: ByteBuffer?, offset: Int, count: Int, timestamp: Long) {
            msg?.let {
                val data = ByteArray(count)
                it.get(data, offset, count)
                setupStreamHandler?.send(mapOf("data" to data, "timestamp" to timestamp))
            }
        }
    }
}
