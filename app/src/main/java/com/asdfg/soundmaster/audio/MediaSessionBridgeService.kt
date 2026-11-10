package com.asdfg.soundmaster.audio

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log
import android.view.KeyEvent

/**
 * Gives AudioPipe access to active MediaSessions once the user grants
 * Notification Access. Media-button events captured by AudioPipe can then
 * be forwarded directly to the selected source app instead of being sent
 * back into Android's global media-button dispatcher.
 */
class MediaSessionBridgeService : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaSessionBridge"

        fun dispatchToPackage(
            context: Context,
            targetPackage: String,
            keyCode: Int
        ): Boolean {
            return try {
                val manager =
                    context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val listenerComponent =
                    ComponentName(context, MediaSessionBridgeService::class.java)

                val controller = manager
                    .getActiveSessions(listenerComponent)
                    .firstOrNull { it.packageName == targetPackage }
                    ?: run {
                        Log.w(TAG, "No active MediaSession for $targetPackage")
                        return false
                    }

                dispatch(controller, keyCode)
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification access not granted", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch media key", e)
                false
            }
        }

        private fun dispatch(controller: MediaController, keyCode: Int): Boolean {
            val controls = controller.transportControls

            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> controls.skipToNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controls.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_PLAY -> controls.play()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> controls.pause()
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    when (controller.playbackState?.state) {
                        PlaybackState.STATE_PLAYING,
                        PlaybackState.STATE_BUFFERING,
                        PlaybackState.STATE_CONNECTING -> controls.pause()
                        else -> controls.play()
                    }
                }
                else -> return false
            }

            Log.d(TAG, "Forwarded key $keyCode to ${controller.packageName}")
            return true
        }
    }
}
