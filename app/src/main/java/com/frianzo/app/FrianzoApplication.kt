package com.frianzo.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log

/**
 * Safety net: if an uncaught exception would kill the app, restart into
 * MainActivity once instead of dying silently. A 10-second loop guard
 * prevents restart loops — if the app already restarted moments ago, the
 * previous (system) handler takes over and the app exits normally.
 * The handler itself is fully guarded: it can never crash the app.
 */
class FrianzoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val lastRestart = prefs.getLong(KEY_LAST_RESTART, 0L)
                val now = System.currentTimeMillis()
                if (now - lastRestart < LOOP_GUARD_MS) {
                    // Restarted very recently — do not loop; exit normally.
                    previousHandler?.uncaughtException(thread, throwable)
                    return@setDefaultUncaughtExceptionHandler
                }
                // commit() (not apply) so the guard survives the process kill.
                prefs.edit().putLong(KEY_LAST_RESTART, now).commit()
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
                }
                startActivity(intent)
                Process.killProcess(Process.myPid())
                System.exit(10)
            } catch (e: Exception) {
                // The safety net itself must never crash the app.
                try {
                    previousHandler?.uncaughtException(thread, throwable)
                } catch (_: Exception) {
                    // Nothing else can be done.
                }
            }
        }
    }

    companion object {
        private const val TAG = "FrianzoApplication"
        private const val PREFS = "frianzo_crash_guard"
        private const val KEY_LAST_RESTART = "last_crash_restart"
        private const val LOOP_GUARD_MS = 10_000L
    }
}
