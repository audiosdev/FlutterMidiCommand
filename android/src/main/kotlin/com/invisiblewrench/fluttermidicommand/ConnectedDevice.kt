package com.invisiblewrench.fluttermidicommand

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.util.Log
import io.flutter.plugin.common.MethodChannel.Result

class ConnectedDevice(
    private val midiDevice: MidiDevice,
    private val setupStreamHandler: FMCStreamHandler,
    context: Context
) : Device(deviceIdForInfo(midiDevice.info), midiDevice.info.type.toString()) {

    private var inputPort: MidiInputPort? = null
    private var outputPort: MidiOutputPort? = null
    private var isOwnVirtualDevice = false
    private var usbDeviceConnection: UsbDeviceConnection? = null
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    override fun connectWithStreamHandler(streamHandler: FMCStreamHandler, connectResult: Result?) {
        Log.d("FlutterMIDICommand", "connectWithHandler")

        this.midiDevice.info?.let {
            Log.d("FlutterMIDICommand", "inputPorts ${it.inputPortCount} outputPorts ${it.outputPortCount}")

            val receiver = RXReceiver(streamHandler, this.midiDevice)
            this.receiver = receiver

            // Assume all devices are USB for this example
            val usbDevices = usbManager.deviceList.values
            for (usbDevice in usbDevices) {
                usbDeviceConnection = usbManager.openDevice(usbDevice)
                Log.d("FlutterMIDICommand", "Connected to USB device: ${usbDevice.deviceName}")
                break
            }

            if (inputPort == null && it.inputPortCount > 0) {
                Log.d("FlutterMIDICommand", "Open input port")
                this.inputPort = this.midiDevice.openInputPort(0)
            }

            if (it.outputPortCount > 0) {
                Log.d("FlutterMIDICommand", "Open output port")
                this.outputPort = this.midiDevice.openOutputPort(0)
                this.outputPort?.connect(this.receiver)
            }
        }

        Handler().postDelayed({
            connectResult?.success(null)
            setupStreamHandler.send("deviceConnected")
        }, 2500)
    }

    override fun send(data: ByteArray, timestamp: Long?) {
        if (isOwnVirtualDevice) {
            Log.d("FlutterMIDICommand", "Send to receiver")
            if (timestamp == null)
                this.receiver?.send(data, 0, data.size)
            else
                this.receiver?.send(data, 0, data.size, timestamp)
        } else {
            this.inputPort?.send(data, 0, data.size, timestamp ?: 0)
        }
    }

    override fun close() {
        Log.d("FlutterMIDICommand", "Flush input port ${this.inputPort}")
        this.inputPort?.flush()
        Log.d("FlutterMIDICommand", "Close input port ${this.inputPort}")
        this.inputPort?.close()
        Log.d("FlutterMIDICommand", "Close output port ${this.outputPort}")
        this.outputPort?.close()
        Log.d("FlutterMIDICommand", "Disconnect receiver ${this.receiver}")
        this.outputPort?.disconnect(this.receiver)
        this.receiver = null
        Log.d("FlutterMIDICommand", "Close device ${this.midiDevice}")
        this.midiDevice.close()

        setupStreamHandler.send("deviceDisconnected")
    }

    class RXReceiver(
        private val stream: FMCStreamHandler,
        device: MidiDevice
    ) : MidiReceiver() {

        private val isBluetoothDevice = device.info.type == MidiDeviceInfo.TYPE_BLUETOOTH
        private val deviceInfo = mapOf(
            "id" to if (isBluetoothDevice) device.info.properties.get(MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE).toString() else device.info.id.toString(),
            "name" to device.info.properties.getString(MidiDeviceInfo.PROPERTY_NAME),
            "type" to if (isBluetoothDevice) "BLE" else "native"
        )

        private enum class PARSER_STATE {
            HEADER, PARAMS, SYSEX
        }

        private var parserState = PARSER_STATE.HEADER
        private val sysExBuffer = mutableListOf<Byte>()
        private val midiBuffer = mutableListOf<Byte>()
        private var midiPacketLength: Int = 0
        private var statusByte: Byte = 0

        override fun onSend(msg: ByteArray?, offset: Int, count: Int, timestamp: Long) {
            msg?.let {
                val data = it.slice(IntRange(offset, offset + count - 1))

                if (data.isNotEmpty()) {
                    for (i in data.indices) {
                        val midiByte = data[i]
                        val midiInt = midiByte.toInt() and 0xFF

                        when (parserState) {
                            PARSER_STATE.HEADER -> {
                                if (midiInt == 0xF0) {
                                    parserState = PARSER_STATE.SYSEX
                                    sysExBuffer.clear()
                                    sysExBuffer.add(midiByte)
                                } else if (midiInt and 0x80 == 0x80) {
                                    statusByte = midiByte
                                    midiPacketLength = lengthOfMessageType(midiInt)
                                    midiBuffer.clear()
                                    midiBuffer.add(midiByte)
                                    parserState = PARSER_STATE.PARAMS
                                    finalizeMessageIfComplete(timestamp)
                                } else {
                                    midiBuffer.clear()
                                    midiBuffer.add(statusByte)
                                    midiBuffer.add(midiByte)
                                    parserState = PARSER_STATE.PARAMS
                                    finalizeMessageIfComplete(timestamp)
                                }
                            }

                            PARSER_STATE.SYSEX -> {
                                if (midiInt == 0xF0) {
                                    sysExBuffer.add(0xF7.toByte())
                                    stream.send(mapOf("data" to sysExBuffer.toList(), "timestamp" to timestamp, "device" to deviceInfo))
                                    sysExBuffer.clear()
                                }
                                sysExBuffer.add(midiByte)
                                if (midiInt == 0xF7) {
                                    stream.send(mapOf("data" to sysExBuffer.toList(), "timestamp" to timestamp, "device" to deviceInfo))
                                    parserState = PARSER_STATE.HEADER
                                }
                            }

                            PARSER_STATE.PARAMS -> {
                                midiBuffer.add(midiByte)
                                finalizeMessageIfComplete(timestamp)
                            }
                        }
                    }
                }
            }
        }

        private fun finalizeMessageIfComplete(timestamp: Long) {
            if (midiBuffer.size == midiPacketLength) {
                stream.send(mapOf("data" to midiBuffer.toList(), "timestamp" to timestamp, "device" to deviceInfo))
                parserState = PARSER_STATE.HEADER
            }
        }

        private fun lengthOfMessageType(type: Int): Int {
            val midiType = type and 0xF0

            return when (type) {
                0xF6, 0xF8, 0xFA, 0xFB, 0xFC, 0xFF, 0xFE -> 1
                0xF1, 0xF3 -> 2
                0xF2 -> 3
                0xC0, 0xD0 -> 2
                0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 3
                else -> 0
            }
        }
    }
}
