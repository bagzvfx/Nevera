package com.nevera.corporation.playback

import android.content.Context
import android.media.AudioAttributes as FrameworkAudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.nevera.corporation.data.model.AudioOutputRoute
import com.nevera.corporation.data.model.AudiophileDspProfile
import com.nevera.corporation.data.store.LibraryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors connected audio output hardware (USB-DAC, Wired IEM, Bluetooth, Speaker),
 * auto-switches per-device Audiophile DSP profiles, and negotiates Android 14+ Bit-Perfect USB-DAC mixer mode.
 */
object AudioHardwareRouter {

    private var audioManager: AudioManager? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var lastSampleRateHz: Int = 48000
    private var activeUsbDevice: AudioDeviceInfo? = null

    private val _activeRoute = MutableStateFlow(AudioOutputRoute.PHONE_SPEAKER)
    val activeRoute: StateFlow<AudioOutputRoute> = _activeRoute.asStateFlow()

    private val _activeDeviceName = MutableStateFlow("Phone Speaker")
    val activeDeviceName: StateFlow<String> = _activeDeviceName.asStateFlow()

    private val _usbBitPerfectActive = MutableStateFlow(false)
    val usbBitPerfectActive: StateFlow<Boolean> = _usbBitPerfectActive.asStateFlow()

    private val frameworkMusicAttrs: FrameworkAudioAttributes by lazy {
        FrameworkAudioAttributes.Builder()
            .setUsage(FrameworkAudioAttributes.USAGE_MEDIA)
            .setContentType(FrameworkAudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    @Synchronized
    fun start(context: Context) {
        if (audioManager != null) {
            refreshActiveDevice()
            return
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am

        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refreshActiveDevice()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refreshActiveDevice()
            }
        }
        deviceCallback = cb
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        refreshActiveDevice()
    }

    @Synchronized
    fun stop() {
        val am = audioManager
        val cb = deviceCallback
        if (am != null && cb != null) {
            try {
                am.unregisterAudioDeviceCallback(cb)
            } catch (_: Exception) {
            }
        }
        clearUsbBitPerfect()
        deviceCallback = null
        audioManager = null
    }

    fun onTrackSampleRateChanged(sampleRateHz: Int) {
        if (sampleRateHz > 0) {
            lastSampleRateHz = sampleRateHz
            reconcileUsbBitPerfect()
        }
    }

    fun onUserProfileEdited(profile: AudiophileDspProfile) {
        val route = _activeRoute.value
        val settings = LibraryStore.settings.value
        LibraryStore.updateSettings { cur ->
            val updatedPerDevice = if (cur.autoSwitchDeviceProfile) {
                cur.perDeviceProfiles + (route.name to profile)
            } else {
                cur.perDeviceProfiles
            }
            cur.copy(
                dspProfile = profile,
                perDeviceProfiles = updatedPerDevice,
                loudnessNormalization = profile.lufsNormalization
            )
        }
        AudiophileDspAudioProcessor.updateLiveProfile(profile)
        reconcileUsbBitPerfect()
        AudioSignalPathBus.onHardwareRouteUpdated(route, _activeDeviceName.value, _usbBitPerfectActive.value)
        if (!settings.autoSwitchDeviceProfile) {
            AudiophileDspAudioProcessor.updateLiveProfile(profile)
        }
    }

    @Synchronized
    fun refreshActiveDevice() {
        val am = audioManager ?: return
        val outputs = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Exception) {
            emptyArray()
        }

        val usb = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        val wired = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
                it.type == AudioDeviceInfo.TYPE_LINE_DIGITAL
        }
        val bt = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }

        val chosenDevice: AudioDeviceInfo?
        val newRoute: AudioOutputRoute
        when {
            usb != null -> {
                chosenDevice = usb
                newRoute = AudioOutputRoute.USB_DAC
            }
            wired != null -> {
                chosenDevice = wired
                newRoute = AudioOutputRoute.WIRED_IEM
            }
            bt != null -> {
                chosenDevice = bt
                newRoute = AudioOutputRoute.BLUETOOTH_AUDIO
            }
            else -> {
                chosenDevice = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                newRoute = AudioOutputRoute.PHONE_SPEAKER
            }
        }

        activeUsbDevice = usb
        val prevRoute = _activeRoute.value
        val deviceName = chosenDevice?.productName?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            ?: newRoute.displayLabel

        _activeRoute.value = newRoute
        _activeDeviceName.value = deviceName

        // Apply saved per-device DSP profile automatically on route change
        val settings = LibraryStore.settings.value
        val targetProfile = if (settings.autoSwitchDeviceProfile && prevRoute != newRoute) {
            val saved = settings.perDeviceProfiles[newRoute.name]
            if (saved != null) {
                LibraryStore.updateSettings { it.copy(dspProfile = saved) }
                saved
            } else {
                settings.dspProfile
            }
        } else {
            settings.dspProfile
        }

        AudiophileDspAudioProcessor.updateLiveProfile(targetProfile)
        reconcileUsbBitPerfect()
        AudioSignalPathBus.onHardwareRouteUpdated(newRoute, deviceName, _usbBitPerfectActive.value)
    }

    private fun reconcileUsbBitPerfect() {
        val usb = activeUsbDevice
        val profile = LibraryStore.settings.value.dspProfile
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            usb != null &&
            profile.bitPerfectBypass
        ) {
            val ok = tryEnableUsbBitPerfectApi34(usb, lastSampleRateHz)
            _usbBitPerfectActive.value = ok
        } else {
            clearUsbBitPerfect()
            _usbBitPerfectActive.value = false
        }
        AudioSignalPathBus.onHardwareRouteUpdated(_activeRoute.value, _activeDeviceName.value, _usbBitPerfectActive.value)
    }

    private fun clearUsbBitPerfect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val am = audioManager ?: return
            val usb = activeUsbDevice ?: return
            try {
                am.clearPreferredMixerAttributes(frameworkMusicAttrs, usb)
            } catch (_: Exception) {
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun tryEnableUsbBitPerfectApi34(usbDevice: AudioDeviceInfo, desiredRateHz: Int): Boolean {
        val am = audioManager ?: return false
        return try {
            val supported = am.getSupportedMixerAttributes(usbDevice)
            val bitPerfectAttrs = supported.filter {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            }
            if (bitPerfectAttrs.isEmpty()) return false
            val chosen = bitPerfectAttrs.minByOrNull {
                kotlin.math.abs(it.format.sampleRate - desiredRateHz)
            } ?: bitPerfectAttrs.first()
            am.setPreferredMixerAttributes(frameworkMusicAttrs, usbDevice, chosen)
        } catch (_: Throwable) {
            false
        }
    }
}
