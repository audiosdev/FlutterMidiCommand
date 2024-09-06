package com.invisiblewrench.fluttermidicommand

import android.content.pm.ServiceInfo
import android.media.midi.*
import android.os.Handler
import android.util.Log
import io.flutter.plugin.common.MethodChannel.Result

class ConnectedDevice : Device {
    var inputPort: MidiInputPort? = null
    var outputPort: MidiOutputPort? = null

    private var isOwnVirtualDevice = false

    constructor(device: MidiDevice, setupStreamHandler: FMCStreamHandler) : super(deviceIdForInfo(device.info), device.info.type.toString()) {
        this.midiDevice = device
        this.setupStreamHandler = setupStreamHandler
    }

    override fun connectWithStreamHandler(streamHandler: FMCStreamHandler, connectResult: Result?) {
        Log.d("FlutterMIDICommand", "connectWithHandler")

        this.midiDevice.info?.let {
            Log.d("FlutterMIDICommand", "inputPorts ${it.inputPortCount} outputPorts ${it.outputPortCount}")

            this.receiver = RXReceiver(streamHandler, this.midiDevice)

            val serviceInfo = it.properties.getParcelable<ServiceInfo>("service_info")
            if (serviceInfo?.name == "com.invisiblewrench.fluttermidicommand.VirtualDeviceService") {
                Log.d("FlutterMIDICommand", "Own virtual")
                isOwnVirtualDevice = true
            } else {
                // Try to open input port if available, or default to port 0 if no ports are reported
                if (it.inputPortCount > 0 || it.inputPortCount == 0) {
                    Log.d("FlutterMIDICommand", "Opening input port or default port 0")
                    this.inputPort = this.midiDevice.openInputPort(0)
                }
            }

            // Try to open output port if available, or default to port 0 if no ports are reported
            if (it.outputPortCount > 0 || it.outputPortCount == 0) {
                Log.d("FlutterMIDICommand", "Opening output port or default port 0")
                this.outputPort = this.midiDevice.openOutputPort(0)
                this.outputPort?.connect(this.receiver)
            }
        }

        Handler().postDelayed({
            connectResult?.success(null)
            setupStreamHandler?.send("deviceConnected")
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

        setupStreamHandler?.send("deviceDisconnected")
    }

    class RXReceiver(stream: FMCStreamHandler, device: MidiDevice) : MidiReceiver() {
        val stream = stream
        var isBluetoothDevice = device.info.type == MidiDeviceInfo.TYPE_BLUETOOTH
        val deviceInfo = mapOf(
            "id" to if (isBluetoothDevice) device.info.properties.get(MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE).toString() else device.info.id.toString(),
            "name" to device.info.properties.getString(MidiDeviceInfo.PROPERTY_NAME),
            "type" to if (isBluetoothDevice) "BLE" else "native"
        )

        // MIDI parsing
        enum class PARSER_STATE {
            HEADER,
            PARAMS,
            SYSEX,
        }

        var parserState = PARSER_STATE.HEADER
        var sysExBuffer = mutableListOf<Byte>()
        var midiBuffer = mutableListOf<Byte>()
        var midiPacketLength: Int = 0
        var statusByte: Byte = 0

        override fun onSend(msg: ByteArray?, offset: Int, count: Int, timestamp: Long) {
            msg?.also {
                val data = it.slice(IntRange(offset, offset + count - 1))
                if (data.isNotEmpty()) {
                    for (i in data.indices) {
                        val midiByte: Byte = data[i]
                        val midiInt = midiByte.toInt() and 0xFF

                        when (parserState) {
                            PARSER_STATE.HEADER -> {
                                if (midiInt == 0xF0) {
                                    parserState = PARSER_STATE.SYSEX
                                    sysExBuffer.clear()
                                    sysExBuffer.add(midiByte)
                                } else if (midiInt and 0x80 == 0x80) {
                                    // Some kind of MIDI message
                                    statusByte = midiByte
                                    midiPacketLength = lengthOfMessageType(midiInt)
                                    midiBuffer.clear()
                                    midiBuffer.add(midiByte)
                                    parserState = PARSER_STATE.PARAMS
                                    finalizeMessageIfComplete(timestamp)
                                } else {
                                    // In header state but no status byte, do running status
                                    midiBuffer.clear()
                                    midiBuffer.add(statusByte)
                                    midiBuffer.add(midiByte)
                                    parserState = PARSER_STATE.PARAMS
                                    finalizeMessageIfComplete(timestamp)
                                }
                            }
                            PARSER_STATE.SYSEX -> {
                                if (midiInt == 0xF0) {
                                    // Android can skip SysEx end bytes, when more SysEx messages are coming in succession.
                                    // In an attempt to save the situation, add an end byte to the current buffer and start a new one.
                                    sysExBuffer.add(0xF7.toByte())
                                    stream.send(
                                        mapOf(
                                            "data" to sysExBuffer.toList(),
                                            "timestamp" to timestamp,
                                            "device" to deviceInfo
                                        )
                                    )
                                    sysExBuffer.clear()
                                }
                                sysExBuffer.add(midiByte)
                                if (midiInt == 0xF7) {
                                    // SysEx complete
                                    stream.send(
                                        mapOf(
                                            "data" to sysExBuffer.toList(),
                                            "timestamp" to timestamp,
                                            "device" to deviceInfo
                                        )
                                    )
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

        fun finalizeMessageIfComplete(timestamp: Long) {
            if (midiBuffer.size == midiPacketLength) {
                stream.send(mapOf("data" to midiBuffer.toList(), "timestamp" to timestamp, "device" to deviceInfo))
                parserState = PARSER_STATE.HEADER
            }
        }

        fun lengthOfMessageType(type: Int): Int {
            val midiType: Int = type and 0xF0

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
