package com.asdfg.soundmaster.audio

import android.media.AudioDeviceInfo
import java.util.Locale

/**
 * A stable, public-API-only description of an audio output.
 * AudioDeviceInfo.id is intentionally not persisted because Android may assign a new id after
 * Android Auto or Bluetooth reconnects the device.
 */
data class AudioOutputPreference(
    val type: Int,
    val productName: String
) {
    fun matches(device: AudioDeviceInfo): Boolean {
        return device.type == type && normalize(device.productName?.toString().orEmpty()) == normalize(productName)
    }

    companion object {
        fun from(device: AudioDeviceInfo): AudioOutputPreference = AudioOutputPreference(
            type = device.type,
            productName = device.productName?.toString().orEmpty()
        )

        private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
    }
}
