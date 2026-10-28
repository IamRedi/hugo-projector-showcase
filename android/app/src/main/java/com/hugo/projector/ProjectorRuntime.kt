package com.hugo.projector

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.media.AudioManager
import org.json.JSONObject
import java.lang.ref.WeakReference

/** The HTTP controller belongs to the application process, not an Activity window. */
object ProjectorRuntime {
    private var controller: LocalController? = null
    private var activity = WeakReference<MainActivity>(null)

    @Synchronized fun ensure(context: Context): LocalController {
        controller?.let { return it }
        PortableMode.initialize(context.applicationContext) {
            activity.get()?.let { screen -> screen.runOnUiThread { screen.refreshNetworkUi() } }
        }
        val server = LocalController(
            context.applicationContext,
            command = { command, payload, done ->
                val screen = activity.get()
                if (screen != null && screen.isProjectionVisible()) screen.runOnUiThread { screen.routeCommand(command, payload, done) }
                else if (command == "status") done(true, "status")
                else if (command == "volume") {
                    val percent = payload.optInt("percent", -1)
                    if (percent !in 0..100) done(false, "percent must be 0..100")
                    else {
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent + 50) / 100, 0)
                        done(true, "volume=$percent")
                    }
                } else done(false, "Projection screen inactive; press Home")
            },
            status = { includeCanvas ->
                activity.get()?.statusJson(includeCanvas) ?: run {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    JSONObject()
                        .put("agentVersion", BuildConfig.VERSION_NAME)
                        .put("model", Build.MODEL)
                        .put("android", Build.VERSION.RELEASE)
                        .put("sceneType", "background")
                        .put("sceneLabel", "")
                        .put("scenePhase", "inactive")
                        .put("screenVisible", false)
                        .put("connected", controller?.url() != null)
                        .put("legacyConnected", false)
                        .put("volumePercent", audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1))
                        .put("localUrl", controller?.url() ?: JSONObject.NULL)
                        .put("networkMode", if (PortableMode.state == "active") "portable" else "home_wifi")
                        .put("portableState", PortableMode.state)
                        .put("display", "1280x720")
                        .put("uptimeMs", SystemClock.elapsedRealtime())
                        .put("canvas", JSONObject.NULL)
                }
            },
            onPairingRequest = { activity.get()?.let { screen -> screen.runOnUiThread { screen.showConnect(false) } } },
            onStateChange = { activity.get()?.let { screen -> screen.runOnUiThread { screen.refreshConnect() } } },
        )
        controller = server
        server.start()
        PortableMode.restoreIfPreferred()
        return server
    }

    @Synchronized fun attach(screen: MainActivity) { activity = WeakReference(screen) }
    @Synchronized fun detach(screen: MainActivity) {
        if (activity.get() === screen) activity.clear()
    }

    @Synchronized fun stopController() {
        controller?.stop()
        controller = null
        PortableMode.stopRuntime()
    }

    fun startService(context: Context) {
        val intent = Intent(context, ProjectionService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
        else context.startService(intent)
    }
}
