package com.tv.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.tv.app.chat.windowListener
import com.tv.app.func.models.ShellExecutorModel
import com.tv.app.ui.suspend.SuspendService
import com.zephyr.global_values.TAG
import com.zephyr.log.LogLevel
import com.zephyr.log.Logger
import com.zephyr.log.logE
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class App : Application() {
    companion object {
        var binder: WeakReference<SuspendService.SuspendServiceBinder> = WeakReference(null)
            private set

        var instance: WeakReference<App> = WeakReference(null)
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Logger.startLogger(this, LogLevel.VERBOSE)
        instance = WeakReference(this)

        ShellExecutorModel.shellManager.executors.forEach { executor ->
            ProcessLifecycleOwner.get().lifecycleScope.launch {
                runCatching {
                    executor.exec("echo test")
                }
            }
        }

        if (hasOverlayPermission())
            startSuspendService()
    }

    private fun startSuspendService() {
        runCatching {
            unbindService(serviceConnection)
        }
        binder.clear()
        val intent = Intent(this, SuspendService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = WeakReference(service as? SuspendService.SuspendServiceBinder)
            logE(TAG, "suspend window binder has connected")

            binder.get()?.setOnTouchEventListener(windowListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder.get()?.setOnTouchEventListener(null)
            binder.clear()
            logE(TAG, "suspend window binder has disconnected")
        }
    }
}