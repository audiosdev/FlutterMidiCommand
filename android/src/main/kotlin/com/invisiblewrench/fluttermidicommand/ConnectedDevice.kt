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
import android.util.Log
import io.flutter.plugin.common.MethodChannel.Result

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
            Log.d("FlutterMIDICommand", "Connecting to USB MIDI device")
            // Assuming you have methods to create and manage USB-MIDI connections
            usbDeviceConnection = usbManager.openDevice(usbDevice)
            // You need to manage USB-MIDI connections directly here
        } else {
            Log.d("FlutterMIDICommand", "Non-USB MIDI device")
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
            Log.d("FlutterMIDICommand", "Open input port")
            inputPort = midiDevice.openInputPort(0)
        }
        if (deviceInfo.outputPortCount > 0) {
            Log.d("FlutterMIDICommand", "Open output port")
            outputPort = midiDevice.openOutputPort(0)
            outputPort?.connect(this.receiver)
        }
    }

    private fun findUsbDevice(deviceInfo: MidiDeviceInfo?): UsbDevice? {
        deviceInfo?.let {
            val usbDevices = usbManager.deviceList
            for (device in usbDevices.values) {
                if (device.vendorId == deviceInfo.properties.getInt("vendorId", -1)) {
                    return device
                }
            }
        }
        return null
    }

    fun send(data: ByteArray, timestamp: Long?) {
        if (usbDeviceConnection != null) {
            // Send data through USB-MIDI connection here
            // For example:
            // usbDeviceConnection?.bulkTransfer(endpoint, data, data.size, 1000)
        } else {
            outputPort?.send(data, 0, data.size)
        }
    }

    fun close() {
        Log.d("FlutterMIDICommand", "Flush and close ports")
        inputPort?.flush()
        inputPort?.close()
        outputPort?.close()
        usbDeviceConnection?.close()
        setupStreamHandler?.send("deviceDisconnected")
    }

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray?, offset: Int, count: Int, timestamp: Long) {
            // Process incoming MIDI messages
            msg?.let {
                val data = it.slice(IntRange(offset, offset + count - 1))
                setupStreamHandler?.send(mapOf("data" to data, "timestamp" to timestamp))
            }
        }
    }
}
