package com.adna.audiorecorderwatch

/**
 * Defines the standard message payloads and constants used within the custom
 * Bluetooth protocol between the watch and the phone.
 * We use a simple object (like a Singleton/static class in Java) to hold these constants.
 */
object Constants {
    // A constant representing the message type "AUDIO" (0x01).
    // The phone expects this integer to know the incoming bytes are voice data.
    const val AUDIO = 1
    
    // A constant representing the message type "TEXT" (0x02).
    // The watch expects this integer to know the incoming bytes are UTF-8 transcript strings.
    const val TEXT = 2

    // The maximum number of bytes we are willing to accept in a single Bluetooth packet.
    // 32 * 1024 calculates out to exactly 32 Kilobytes (32,768 bytes).
    // This prevents malicious or corrupted packets from causing memory crashes.
    const val MAX_PAYLOAD_BYTES = 32 * 1024
}
