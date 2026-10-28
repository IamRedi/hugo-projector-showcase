package com.hugo.projector

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import com.hugo.projector.fx.*

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var localController: LocalController
    private lateinit var canvasEngine: CanvasEngine
    private var arcadeView: ArcadeView? = null
    private var previousGameScene: String? = null
    private var canvasView: ProjectionCanvasView? = null
    private var fxSession: FxSession? = null
    private var fxView: FxRenderer? = null
    private var previousFxScene: String? = null
    private lateinit var audio: AudioManager
    private val generation = AtomicInteger()
    private var currentWebView: WebView? = null
    private var currentVideo: VideoView? = null
    private var mediaBox: FrameLayout? = null
    @Volatile private var mediaPlacement=MediaPlacement(0.0,0.0,1.0,1.0)
    @Volatile private var mediaScene: JSONObject? = null
    @Volatile private var mediaState="STOPPED"
    @Volatile private var sceneType = "idle"
    @Volatile private var sceneLabel = ""
    @Volatile private var scenePhase = "ready"
    @Volatile private var screenVisible = false
    private var restoredScene = false
    private var homePage = "home"
    private val portablePermissionRequest = 2101
    private val connectTimer = Handler(Looper.getMainLooper())
    private val connectTick = object : Runnable {
        override fun run() {
            if (sceneType == "home" && homePage == "connect") showConnect(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        canvasEngine = CanvasEngine(applicationContext)
        TankDuel.initialize(applicationContext)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        localController = ProjectorRuntime.ensure(applicationContext)
        ProjectorRuntime.attach(this)
        ProjectorRuntime.startService(applicationContext)
        val saved = getSharedPreferences("hugo_scene", MODE_PRIVATE).getString("last_scene", null)
        if (saved != null) {
            try {
                val scene = JSONObject(saved)
                if (scene.optString("kind") == "canvas") scene.put("model", canvasEngine.snapshot())
                render(scene) { _, _ -> }
            } catch (e: Exception) { Log.w("HUGO_V2", "Scene restore failed", e); showHome() }
        } else showHome()
        Log.i("HUGO_V2", "Agent V2 started on ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    }

    override fun onResume() {
        super.onResume()
        screenVisible = true
        ProjectorRuntime.attach(this)
    }

    override fun onPause() {
        screenVisible = false
        TankDuel.pause()
        if(mediaBox!=null && currentVideo?.isPlaying==true) { currentVideo?.pause();mediaState="PAUSED" }
        fxSession?.pause()
        fxView?.changed()
        super.onPause()
    }

    internal fun isProjectionVisible(): Boolean = screenVisible

    internal fun routeCommand(command: String, payload: JSONObject, done: (Boolean, String) -> Unit) {
        try {
            when (command) {
                "game_start" -> {
                    if (arcadeView == null) previousGameScene = getSharedPreferences("hugo_scene", MODE_PRIVATE).getString("last_scene", null)
                    disposeScene()
                    TankDuel.start(payload)
                    val view = ArcadeView(this); root.addView(view, FrameLayout.LayoutParams(-1,-1)); arcadeView = view
                    sceneType = "game"; scenePhase = "ready"; done(true, "Tank Duel lobby")
                }
                "game_pause" -> { TankDuel.pause(); done(true, "paused") }
                "game_resume" -> { TankDuel.resume(); done(true, "running") }
                "game_exit" -> { exitGame(); done(true, "previous scene restored") }

                "show" -> render(payload, done)
                "media_place" -> {
                    require(mediaBox!=null && mediaScene!=null) { "Open a photo or video first" }
                    val placement=MediaPlacement.parse(payload.getJSONObject("placement"))
                    val commit=if(payload.has("persist"))payload.getBoolean("persist") else true
                    mediaPlacement=placement; applyMediaPlacement()
                    val updated=JSONObject(mediaScene.toString()).put("placement",placement.json());mediaScene=updated
                    if(commit)getSharedPreferences("hugo_scene",MODE_PRIVATE).edit().putString("last_scene",updated.toString()).apply()
                    done(true,"media position updated")
                }
                "media_pause" -> { require(mediaBox!=null);currentVideo?.pause();mediaState="PAUSED";done(true,"paused") }
                "media_resume" -> { require(mediaBox!=null);mediaBox?.visibility=View.VISIBLE;currentVideo?.start();mediaState="PLAYING";persistMediaStop(false);done(true,"playing") }
                "media_stop" -> { require(mediaBox!=null);currentVideo?.pause();mediaBox?.visibility=View.INVISIBLE;mediaState="STOPPED";persistMediaStop(true);done(true,"blackout") }
                "clear" -> {
                    generation.incrementAndGet()
                    disposeScene()
                    sceneType = "clear"
                    sceneLabel = ""
                    scenePhase = "ready"
                    getSharedPreferences("hugo_scene", MODE_PRIVATE).edit().remove("last_scene").apply()
                    done(true, "cleared")
                }
                "volume" -> {
                    val percent = payload.optInt("percent", -1)
                    require(percent in 0..100) { "percent must be 0..100" }
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent + 50) / 100, 0)
                    done(true, "volume=$percent")
                }
                "status" -> done(true, "status")
                "canvas_batch" -> {
                    val started = SystemClock.uptimeMillis()
                    val detail = canvasEngine.batch(payload)
                    val modelMs = SystemClock.uptimeMillis() - started
                    showCanvasModel()
                    Log.i("HUGO_V2", "canvas batch modelMs=$modelMs viewMs=${SystemClock.uptimeMillis() - started - modelMs}")
                    done(true, detail)
                }
                "canvas_undo" -> { val changed = canvasEngine.undo(); showCanvasModel(); done(changed, if (changed) "undo applied" else "nothing to undo") }
                "canvas_redo" -> { val changed = canvasEngine.redo(); showCanvasModel(); done(changed, if (changed) "redo applied" else "nothing to redo") }
                "mapping" -> { canvasEngine.mapping(payload); showCanvasModel(); done(true, "mapping saved") }
                "animation" -> { canvasEngine.animation(payload); showCanvasModel(true); done(true, "effect started") }
                else -> if(command in FxSceneGraph.commands) routeFx(command,payload,done) else done(false, "unsupported command")
            }
        } catch (e: Exception) {
            Log.e("HUGO_V2", "Command failed: $command", e)
            done(false, e.message ?: "command failed")
        }
    }

    internal fun statusJson(includeCanvas: Boolean = true): JSONObject {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return JSONObject()
            .put("media",mediaScene?.let { JSONObject().put("kind",it.optString("kind")).put("src",it.optString("src")).put("name",it.optString("name")).put("placement",mediaPlacement.json()).put("state",mediaState).put("renderId",generation.get()).put("positionMs",runCatching { currentVideo?.currentPosition?:0 }.getOrDefault(0)) }?:JSONObject.NULL)
            .put("fxEnabled",BuildConfig.FX_ENABLED)
            .put("fx",fxSession?.status()?.put("render",fxView?.statistics()?:JSONObject.NULL)?:JSONObject().put("phase","IDLE"))
            .put("game", TankDuel.state())
            .put("agentVersion", BuildConfig.VERSION_NAME)
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("sceneType", sceneType)
            .put("sceneLabel", if (sceneType == "home") "" else sceneLabel)
            .put("scenePhase", scenePhase)
            .put("screenVisible", screenVisible)
            .put("connected", if (::localController.isInitialized) localController.url() != null else false)
            .put("legacyConnected", false)
            .put("volumePercent", audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
            .put("uptimeMs", SystemClock.elapsedRealtime())
            .put("display", "1280x720")
            .put("localUrl", if (::localController.isInitialized) localController.url() else JSONObject.NULL)
            .put("networkMode", if (PortableMode.state == "active") "portable" else "home_wifi")
            .put("portableState", PortableMode.state)
            .put("canvas", if (includeCanvas && ::canvasEngine.isInitialized && sceneType == "canvas") canvasEngine.snapshot() else JSONObject.NULL)
            .put("lastFrameUptimeMs", canvasView?.lastFrameAt ?: JSONObject.NULL)
            .put("canvasFps", canvasView?.measuredFps ?: JSONObject.NULL)
    }

    private fun showIdle(message: String) {
        disposeScene()
        sceneType = "idle"
        scenePhase = "ready"
        val box = TextView(this).apply {
            text = "HUGO\nPROJECTOR\n\n$message"
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setBackgroundColor(Color.BLACK)
        }
        root.addView(box, FrameLayout.LayoutParams(-1, -1))
    }

    private fun baseHome(title: String): LinearLayout {
        disposeScene()
        sceneType = "home"
        scenePhase = "ready"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(80, 35, 80, 35)
            setBackgroundColor(Color.rgb(8, 15, 30))
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 44f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(box) }, FrameLayout.LayoutParams(-1, -1))
        return box
    }

    private fun homeButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 20f
        setOnClickListener { action() }
        isAllCaps = false
    }

    private fun showHome() {
        homePage = "home"
        val box = baseHome("HUGO PROJECTOR")
        box.addView(TextView(this).apply {
            text = localController.url() ?: "Connect projector to Wi-Fi"
            setTextColor(Color.rgb(159, 194, 247))
            textSize = 22f
            gravity = Gravity.CENTER
        })
        box.addView(homeButton("Connect phone") { showConnect(true) })
        box.addView(homeButton("Portable Mode") { startPortableMode() })
        box.addView(homeButton("Games - Tank Duel") {
            val boxGame = baseHome("TANK DUEL")
            boxGame.addView(TextView(this).apply { text = "Open Games on your paired phone to configure and start."; setTextColor(Color.WHITE); textSize = 24f; gravity = Gravity.CENTER })
            boxGame.addView(homeButton("Back") { showHome() })
        })
        box.addView(homeButton("Local scenes") { showLocalScenes() })
        box.addView(homeButton("Settings") { showSettings() })
    }

    internal fun showConnect(newCode: Boolean) {
        homePage = "connect"
        if (newCode) {
            connectTimer.removeCallbacks(connectTick)
            connectTimer.postDelayed(connectTick, 120_500)
        }
        if (PortableMode.state != "off") { showPortableConnect(newCode); return }
        if (newCode) localController.activatePairing()
        val box = baseHome("CONNECT PHONE")
        val address = localController.url()
        val code = localController.activeCode()
        if (address != null && code != null) {
            val qrTarget = address + "?pair=$code"
            try {
                val matrix = QRCodeWriter().encode(qrTarget, BarcodeFormat.QR_CODE, 244, 244)
                val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                box.addView(ImageView(this).apply { setImageBitmap(bitmap) }, LinearLayout.LayoutParams(-1, 250))
            } catch (e: Exception) { Log.w("HUGO_V2", "QR unavailable", e) }
            box.addView(TextView(this).apply {
                text = "$address\nPair code: $code  •  ${localController.pairSecondsLeft()}s"
                setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
            })
        } else box.addView(TextView(this).apply { text = "Connect projector to Wi-Fi"; setTextColor(Color.WHITE); textSize = 24f })
        val pending = localController.pendingName()
        if (pending != null) {
            box.addView(TextView(this).apply { text = "Pair request from $pending"; setTextColor(Color.YELLOW); textSize = 25f; gravity = Gravity.CENTER })
            box.addView(homeButton("Approve this phone") { localController.approvePairing(); showHome() })
            box.addView(homeButton("Reject") { localController.rejectPairing(); showConnect(false) })
        }
        box.addView(homeButton("New code") { showConnect(true) })
        box.addView(homeButton("Back") { showHome() })
    }

    private fun showPortableConnect(newCode: Boolean) {
        if (newCode) localController.activatePairing()
        val box = baseHome("PORTABLE MODE / CONNECT PHONE")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val active = PortableMode.state == "active"
        val address = if (active) PortableMode.hotspotAddress?.let { "http://$it:39090/" } else null
        val code = localController.activeCode()
        if (active && PortableMode.ssid.isNotEmpty()) {
            val escape: (String) -> String = { it.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:") }
            val password = PortableMode.passphrase
            row.addView(connectQr("PROJECTOR WI-FI", "WIFI:T:${if (password.isEmpty()) "nopass" else "WPA"};S:${escape(PortableMode.ssid)};P:${escape(password)};;"))
        }
        if (address != null) row.addView(connectQr("CONTROLLER", if (code != null) "$address?pair=$code" else address))
        box.addView(row, LinearLayout.LayoutParams(-1, 205))
        val info = when {
            active && address != null -> "1. Join ${PortableMode.ssid}  Password: ${PortableMode.passphrase}\n2. Open $address  3. Pair: ${code ?: "closed"}"
            active -> "Hotspot active. Waiting for local IP.\nWi-Fi: ${PortableMode.ssid}  Password: ${PortableMode.passphrase}"
            else -> "Portable Mode: ${PortableMode.state}. ${PortableMode.error}"
        }
        box.addView(TextView(this).apply { text = info; setTextColor(Color.WHITE); textSize = 19f; gravity = Gravity.CENTER })
        if (code != null) box.addView(TextView(this).apply { text = "Pairing closes in ${localController.pairSecondsLeft()}s"; setTextColor(Color.YELLOW); textSize = 16f; gravity = Gravity.CENTER })
        localController.pendingName()?.let { name ->
            box.addView(TextView(this).apply { text = "Pair request from $name"; setTextColor(Color.YELLOW); textSize = 21f; gravity = Gravity.CENTER })
            box.addView(homeButton("Approve this phone") { localController.approvePairing(); showHome() })
            box.addView(homeButton("Reject") { localController.rejectPairing(); showPortableConnect(false) })
        }
        box.addView(homeButton("New pairing code") { showPortableConnect(true) })
        if (active || PortableMode.state == "starting") box.addView(homeButton("Home Wi-Fi (stop hotspot)") { PortableMode.stop(); showConnect(false) })
        else box.addView(homeButton("Retry Portable Mode") { startPortableMode() })
        box.addView(homeButton("Back") { showHome() })
    }

    private fun connectQr(label: String, contents: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(TextView(this@MainActivity).apply { text = label; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER })
        try {
            val matrix = QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, 180, 180)
            val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            addView(ImageView(this@MainActivity).apply { setImageBitmap(bitmap) }, LinearLayout.LayoutParams(190, 180))
        } catch (e: Exception) { Log.w("HUGO_V2", "QR unavailable", e) }
    }

    private fun startPortableMode() {
        val missing = PortableMode.permissionMissing()
        if (missing.isNotEmpty()) requestPermissions(missing, portablePermissionRequest)
        else PortableMode.start()
        showConnect(false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == portablePermissionRequest) {
            if (PortableMode.permissionMissing().isEmpty()) PortableMode.start()
            showConnect(false)
        }
    }

    internal fun refreshNetworkUi() {
        if (sceneType == "home") when (homePage) {
            "connect" -> showConnect(false)
            "home" -> showHome()
        }
    }

    internal fun refreshConnect() {
        if (sceneType == "home" && homePage == "connect" && localController.pendingName() != null) showConnect(false)
    }

    private fun showLocalScenes() {
        homePage = "scenes"
        val box = baseHome("LOCAL SCENES")
        val scenes = localController.store.scenes()
        for (i in 0 until minOf(scenes.length(), 8)) {
            val item = scenes.getJSONObject(i)
            box.addView(homeButton(item.optString("name")) { render(item.getJSONObject("scene")) { _, _ -> } })
        }
        box.addView(homeButton("Back") { showHome() })
    }

    private fun showSettings() {
        homePage = "settings"
        val box = baseHome("SETTINGS")
        box.addView(homeButton("System settings") {
            try { startActivity(android.content.Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings")) }
            catch (e: Exception) { Log.w("HUGO_V2", "System settings unavailable", e) }
        })
        box.addView(homeButton("Original launcher") {
            try {
                val launch = packageManager.getLaunchIntentForPackage("com.htc.luminaos")
                    ?: android.content.Intent().setClassName("com.htc.luminaos", "com.htc.luminaos.activity.MainActivity")
                startActivity(launch)
            } catch (e: Exception) { Log.w("HUGO_V2", "Original launcher unavailable", e) }
        })
        val clients = localController.vault.clients()
        for (i in 0 until clients.length()) {
            val item = clients.getJSONObject(i)
            box.addView(homeButton("Revoke: ${item.optString("name")}") { localController.revoke(item.optString("id")); showSettings() })
        }
        box.addView(homeButton("Back") { showHome() })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if(fxView!=null) exitFx() else if (arcadeView != null) exitGame() else showHome() }

    private fun ensureFxView(): FxRenderer {
        require(BuildConfig.FX_ENABLED) { "FX is disabled" }
        fxView?.let { return it }
        previousFxScene=getSharedPreferences("hugo_scene",MODE_PRIVATE).getString("last_scene",null)
        generation.incrementAndGet()
        disposeScene()
        val s=fxSession?:FxSession(applicationContext).also { fxSession=it }
        return FxRenderer(this,s).also { root.addView(it,FrameLayout.LayoutParams(-1,-1)); fxView=it; sceneType="fx"; sceneLabel="Sofa Spirit"; scenePhase="ready" }
    }

    private fun exitFx() {
        val saved=previousFxScene
        if(saved!=null) render(JSONObject(saved)) { _,_-> } else showHome()
        previousFxScene=null
    }

    private fun routeFx(command: String,payload: JSONObject,done: (Boolean,String)->Unit) {
        require(BuildConfig.FX_ENABLED) { "FX is disabled" }
        val s=fxSession?:FxSession(applicationContext).also { fxSession=it }
        when(command) {
            "fx.list","fx.status" -> Unit
            "fx.calibration.create" -> {
                val p=FxSceneGraph.defaults()
                if(payload.has("name"))p.put("target_label",payload.getString("name"))
                if(payload.has("id"))p.put("calibration_profile_id",payload.getString("id"))
                s.calibrate(JSONObject().put("profile",p)); ensureFxView().prepare()
            }
            "fx.calibration.update" -> { s.calibrate(payload); ensureFxView().prepare() }
            "fx.calibration.reset" -> { s.resetPatch(payload.getString("patch")); ensureFxView().prepare() }
            "fx.calibration.save" -> { s.setProfile(s.store.save(payload.optJSONObject("profile")?:s.profile)); fxView?.prepare() }
            "fx.calibration.load" -> { val p=s.store.load(payload.getString("id")); s.calibrate(JSONObject().put("profile",p)); ensureFxView().prepare() }
            "fx.settings","fx.setIntensity" -> { s.settings(payload); fxView?.changed() }
            "fx.play","fx.replay" -> { s.settings(payload); val view=ensureFxView(); s.play(); view.resetMetrics(); view.changed() }
            "fx.preview" -> { val view=ensureFxView(); s.preview(); view.changed() }
            "fx.pause" -> { s.pause(); fxView?.changed() }
            "fx.resume" -> { require(fxView!=null) { "FX is not active" }; s.resume(); fxView?.changed() }
            "fx.stop" -> { s.stop(); fxView?.changed() }
            "fx.exit" -> if(fxView!=null)exitFx()
            "fx.scene.save" -> {
                val name=payload.optString("name",s.profile.getString("target_label"))
                require(name.isNotBlank() && name.length<=80) { "Invalid scene name" }
                s.setProfile(s.store.save(payload.optJSONObject("profile")?:s.profile))
                localController.store.save(name,s.scene()); fxView?.prepare()
            }
        }
        done(true,"$command applied; lastFrameUptimeMs reports drawn state")
    }

    private fun exitGame() {
        val saved = previousGameScene
        TankDuel.exit()
        if (saved != null) render(JSONObject(saved)) { _, _ -> } else showHome()
        previousGameScene = null
    }

    private fun disposeScene() {
        mediaBox=null;mediaScene=null;mediaState="STOPPED"
        fxView?.release(); if(fxView!=null)fxSession?.stop(); fxView=null
        if (arcadeView != null) { TankDuel.exit(); arcadeView = null }
        canvasView = null
        currentVideo?.stopPlayback()
        currentVideo = null
        currentWebView?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
        currentWebView = null
        root.removeAllViews()
        root.setBackgroundColor(Color.BLACK)
    }

    private fun showCanvasModel(restartAnimation: Boolean = false) {
        if (canvasView == null) {
            val view = ProjectionCanvasView(this)
            showView(view)
            canvasView = view
        }
        sceneType = "canvas"
        scenePhase = "ready"
        canvasView?.setModel(canvasEngine.modelForRender(), restartAnimation)
        getSharedPreferences("hugo_scene", MODE_PRIVATE).edit().putString("last_scene", JSONObject().put("kind", "canvas").toString()).apply()
    }

    private fun showView(view: View) {
        disposeScene()
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showMedia(view: View,payload: JSONObject) {
        val placement=MediaPlacement.parse(payload.optJSONObject("placement"))
        val holder=FrameLayout(this).apply { setBackgroundColor(Color.BLACK);clipChildren=true }
        val box=FrameLayout(this).apply { clipChildren=true;setBackgroundColor(Color.BLACK) }
        box.addView(view,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        holder.addView(box,FrameLayout.LayoutParams(1,1))
        showView(holder);mediaBox=box;mediaScene=JSONObject(payload.toString()).put("placement",placement.json());mediaPlacement=placement
        mediaState=if(payload.optBoolean("stopped",false))"STOPPED" else "PLAYING"
        box.visibility=if(mediaState=="STOPPED")View.INVISIBLE else View.VISIBLE
        holder.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_->if(mediaBox===box)applyMediaPlacement() }
        applyMediaPlacement()
    }

    private fun applyMediaPlacement() {
        val box=mediaBox?:return;val parent=box.parent as? View?:return
        val w=parent.width.takeIf { it>0 }?:1280;val h=parent.height.takeIf { it>0 }?:720
        val scale=minOf(w/1280.0,h/720.0);val stageW=1280*scale;val stageH=720*scale;val p=mediaPlacement
        box.layoutParams=FrameLayout.LayoutParams((stageW*p.width).toInt().coerceAtLeast(1),(stageH*p.height).toInt().coerceAtLeast(1)).apply { leftMargin=((w-stageW)/2+p.x*stageW).toInt();topMargin=((h-stageH)/2+p.y*stageH).toInt() }
    }

    private fun persistMediaStop(stopped: Boolean) {
        val next=JSONObject(mediaScene?.toString()?:return)
        if(stopped)next.put("stopped",true) else next.remove("stopped")
        mediaScene=next;getSharedPreferences("hugo_scene",MODE_PRIVATE).edit().putString("last_scene",next.toString()).apply()
    }

    private fun color(value: String?, fallback: Int): Int = try {
        if (value.isNullOrBlank()) fallback else Color.parseColor(value)
    } catch (_: Exception) { fallback }

    private fun render(payload: JSONObject, done: (Boolean, String) -> Unit) {
        val type = payload.optString("kind")
        val serial = generation.incrementAndGet()
        val complete = AtomicBoolean(false)
        val finish: (Boolean, String) -> Unit = { ok, detail ->
            if (detail != "page loading") scenePhase = if (ok) "ready" else "error"
            if (complete.compareAndSet(false, true)) done(ok, detail)
        }
        sceneType = type
        sceneLabel = payload.optString("name", "")
        scenePhase = "loading"
        Log.i("HUGO_AGENT", "Rendering $type scene=$sceneLabel")
        when (type) {
            "fx" -> {
                require(BuildConfig.FX_ENABLED) { "FX is disabled" }
                FxSceneGraph.validateScene(payload)
                val s=fxSession?:FxSession(applicationContext).also { fxSession=it }
                val p=s.store.load(payload.getString("profileId"))
                val view=ensureFxView(); s.setProfile(p); s.settings(payload); s.play(); view.prepare(); view.resetMetrics(); view.changed()
                finish(true,"FX queued; observe lastFrameUptimeMs")
            }
            "text" -> {
                val text = payload.optString("text", "")
                val view = TextView(this).apply {
                    this.text = text
                    setTextColor(color(payload.optString("color"), Color.WHITE))
                    setBackgroundColor(color(payload.optString("background"), Color.BLACK))
                    textSize = payload.optDouble("fontSize", 64.0).toFloat().coerceIn(16f, 160f)
                    gravity = Gravity.CENTER
                    setPadding(64, 40, 64, 40)
                }
                showView(view)
                finish(true, "text rendered")
            }
            "image" -> {
                MediaPlacement.parse(payload.optJSONObject("placement"))
                val source = mediaUrl(payload.getString("src"))
                val view = ImageView(this).apply {
                    setBackgroundColor(Color.BLACK)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                showMedia(view,payload)
                thread(name = "hugo-image", isDaemon = true) {
                    val file = File(cacheDir, "hugo-image-$serial")
                    try {
                        val http = URL(source).openConnection() as HttpURLConnection
                        if (source.startsWith("http://127.0.0.1:39090/media/"))
                            http.setRequestProperty("X-Hugo-Internal", localController.internalMediaSecret)
                        http.connectTimeout = 8_000
                        http.readTimeout = 15_000
                        http.inputStream.use { input ->
                            file.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > 25_000_000) throw IllegalArgumentException("Image exceeds 25 MB")
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        http.disconnect()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1080) sample *= 2
                        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                            ?: throw IllegalArgumentException("Cannot decode image")
                        runOnUiThread {
                            if (generation.get() == serial) {
                                view.setImageBitmap(bitmap)
                                finish(true, "image rendered")
                            } else bitmap.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e("HUGO_AGENT", "Image load failed", e)
                        runOnUiThread { if (generation.get() == serial) finish(false, e.message ?: "image load failed") }
                    } finally { file.delete() }
                }
            }
            "html", "page" -> {
                val view = WebView(this)
                view.setBackgroundColor(Color.BLACK)
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.allowFileAccess = false
                view.settings.allowContentAccess = false
                view.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(webView: WebView?, url: String?) {
                        if (generation.get() == serial && scenePhase != "error") finish(true, "page loaded")
                    }
                    override fun onReceivedError(webView: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (generation.get() == serial && request?.isForMainFrame == true) scenePhase = "error"
                    }
                }
                showView(view)
                currentWebView = view
                if (type == "html") {
                    view.loadDataWithBaseURL(null, payload.optString("html", ""), "text/html", "UTF-8", null)
                } else {
                    val url = payload.getString("url")
                    require(url.startsWith("https://") || url.startsWith("http://")) { "Only HTTP(S) pages are supported" }
                    view.loadUrl(url)
                }
                // Loading may continue after this ACK; status remains observable.
                finish(true, "page loading")
            }
            "video" -> {
                MediaPlacement.parse(payload.optJSONObject("placement"))
                val source = mediaUrl(payload.getString("src"))
                val view = VideoView(this)
                showMedia(view,payload)
                currentVideo = view
                view.setOnPreparedListener { player ->
                    player.isLooping = payload.optBoolean("loop", true)
                    if(mediaState=="PLAYING")view.start() else view.pause()
                    if (generation.get() == serial) finish(true, "video playing")
                }
                view.setOnErrorListener { _, what, extra ->
                    if (generation.get() == serial) finish(false, "video error $what/$extra")
                    true
                }
                if (source.startsWith("http://127.0.0.1:39090/media/"))
                    view.setVideoURI(Uri.parse(source), mapOf("X-Hugo-Internal" to localController.internalMediaSecret))
                else view.setVideoURI(Uri.parse(source))
            }
            "canvas" -> {
                val model = payload.optJSONObject("model")
                if (model != null) canvasEngine.replace(model)
                showCanvasModel(true)
                finish(true, "canvas model rendered")
            }
            else -> throw IllegalArgumentException("Unknown scene kind: $type")
        }
        if (type.isNotEmpty() && type!="fx") getSharedPreferences("hugo_scene", MODE_PRIVATE).edit().putString("last_scene", payload.toString()).apply()
    }

    private fun mediaUrl(source: String): String = if (source.startsWith("/media/")) "http://127.0.0.1:39090$source" else source

    override fun onDestroy() {
        connectTimer.removeCallbacks(connectTick)
        ProjectorRuntime.detach(this)
        if (::root.isInitialized) disposeScene()
        super.onDestroy()
    }
}
