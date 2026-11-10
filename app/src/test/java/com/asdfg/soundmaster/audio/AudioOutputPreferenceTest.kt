package com.asdfg.soundmaster.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AudioOutputPreferenceTest {
    @Test
    fun matchesSameTypeAndNormalizedName() {
        val device = mock<AudioDeviceInfo>()
        whenever(device.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        whenever(device.productName).thenReturn("Sony WH-1000XM3")

        val preference = AudioOutputPreference(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            "  sony wh-1000xm3  "
        )

        assertTrue(preference.matches(device))
    }

    @Test
    fun rejectsDifferentDeviceName() {
        val device = mock<AudioDeviceInfo>()
        whenever(device.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        whenever(device.productName).thenReturn("Android Auto")

        val preference = AudioOutputPreference(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            "SWL-BT_5BBD"
        )

        assertFalse(preference.matches(device))
    }
}
