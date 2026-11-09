package com.davenn.carbuddy

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet

/**
 * App-side connection to [DisplayService] through Shizuku. Shared by the phone
 * screen and the car session, which live in the same process.
 *
 * All callbacks arrive on the main thread.
 */
object ShizukuBridge {

    enum class State { NOT_RUNNING, NEEDS_PERMISSION, CONNECTING, READY }

    var service: IDisplayService? = null
        private set

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var binding = false
    private var started = false

    private val args = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, DisplayService::class.java.name)
    )
        .daemon(false) // dies with the app, so a new install never talks to an old service
        .processNameSuffix("display")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = if (binder != null && binder.pingBinder()) {
                IDisplayService.Stub.asInterface(binder)
            } else null
            notifyListeners()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            service = null
            notifyListeners()
        }
    }

    fun start() {
        if (started) return
        started = true
        Shizuku.addBinderReceivedListenerSticky { bindIfAllowed(); notifyListeners() }
        Shizuku.addBinderDeadListener {
            service = null
            binding = false
            notifyListeners()
        }
        Shizuku.addRequestPermissionResultListener { _, _ ->
            bindIfAllowed()
            notifyListeners()
        }
    }

    fun state(): State = when {
        !Shizuku.pingBinder() -> State.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NEEDS_PERMISSION
        service == null -> State.CONNECTING
        else -> State.READY
    }

    fun requestPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_CODE)
    }

    fun bindIfAllowed() {
        if (service != null || binding) return
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
        binding = true
        try {
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            binding = false
        }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun notifyListeners() = listeners.forEach { it() }

    fun describe(state: State = state()): String = when (state) {
        State.NOT_RUNNING -> "Shizuku is not running. Open Shizuku on the phone and start it."
        State.NEEDS_PERMISSION -> "carBuddy needs Shizuku permission. Open carBuddy on the phone."
        State.CONNECTING -> "Connecting to Shizuku…"
        State.READY -> "Ready"
    }

    private const val REQUEST_CODE = 42
}
