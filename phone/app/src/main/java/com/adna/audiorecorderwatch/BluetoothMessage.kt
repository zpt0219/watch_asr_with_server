package com.adna.audiorecorderphone

/**
 * Defines the standard message payloads and constants used within the custom
 * Bluetooth protocol between the watch and the phone.
 * 
 * We use an 'object' (like a static Singleton in Java) to hold these values globally.
 */
object BluetoothMessage {
    // 0x01: Tag telling the listener that the incoming bytes are voice audio data.
    const val AUDIO = 1
    
    // The strict hardware limit on how many bytes we accept in a single Bluetooth packet.
    // 32 * 1024 equals exactly 32 Kilobytes (32,768 bytes).
    // This protects us from getting overloaded by a corrupted endless packet.
    const val MAX_PAYLOAD_BYTES = 32 * 1024
}
