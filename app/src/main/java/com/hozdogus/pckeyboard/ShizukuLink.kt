package com.hozdogus.pckeyboard

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** Connects to Shizuku and sends key presses through it. */
object ShizukuLink {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, CONNECTING, READY }

    @Volatile
    private var injector: IKeyInjector? = null
    private var binding = false
    private val worker = Executors.newSingleThreadExecutor()
    private var listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() { listeners.toList().forEach { it() } }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun isRunning(): Boolean = try { Shizuku.pingBinder() } catch (t: Throwable) { false }

    fun hasPermission(): Boolean = isRunning() &&
        try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (t: Throwable) { false }

    fun state(context: Context): State = when {
        injector != null && isRunning() -> State.READY
        !isRunning() -> if (isInstalled(context)) State.NOT_RUNNING else State.NOT_INSTALLED
        !hasPermission() -> State.NO_PERMISSION
        else -> State.CONNECTING
    }

    fun requestPermission(code: Int) {
        try { Shizuku.requestPermission(code) } catch (t: Throwable) { }
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName("com.hozdogus.pckeyboard", KeyInjectorService::class.java.name))
            .daemon(false)
            .processNameSuffix("keys")
            .debuggable(false)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            injector = if (service != null && service.pingBinder()) IKeyInjector.Stub.asInterface(service) else null
            notifyChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            injector = null
            notifyChanged()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { connect() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        injector = null
        binding = false
        notifyChanged()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        connect()
        notifyChanged()
    }
    private var watching = false

    /** Starts watching Shizuku and connects as soon as it's running and allowed. */
    fun start() {
        if (!watching) {
            watching = true
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
        }
        connect()
    }

    fun connect() {
        if (injector != null || binding || !hasPermission()) return
        binding = true
        try {
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            binding = false
        }
        notifyChanged()
    }

    /** Sends one key event in the background, keeping the order. */
    fun send(action: Int, keyCode: Int, meta: Int, downTime: Long, repeat: Int = 0) {
        val inj = injector ?: return
        worker.execute {
            try {
                inj.injectKey(action, keyCode, meta, downTime, repeat)
            } catch (t: Throwable) {
                // Shizuku went away; the dead listener will update the state.
            }
        }
    }

    fun ready(): Boolean = injector != null
}
