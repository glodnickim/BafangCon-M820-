package com.test.bafangcon

import android.app.Application
import android.content.Intent
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Klasa Application instalująca globalny handler nieobsłużonych wyjątków.
 * Zamiast cichego zamknięcia, aplikacja pokazuje [CrashActivity] z pełnym stack trace,
 * żeby przyczynę crashu dało się odczytać bez adb/logcat (instalacja ręczna APK).
 *
 * Po pomyślnej diagnozie ten mechanizm można usunąć (klasa + wpis w manifeście + CrashActivity).
 */
class BafangApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Nie instaluj handlera w procesie :crash (uniknięcie pętli, gdyby sam ekran padł).
        if (isCrashProcess()) return

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val trace = "Thread: ${thread.name}\n\n$sw"

                val intent = Intent(applicationContext, CrashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(CrashActivity.EXTRA_TRACE, trace)
                applicationContext.startActivity(intent)
            } catch (_: Throwable) {
                // Jeśli pokazanie ekranu się nie uda, oddaj sterowanie domyślnemu handlerowi.
                previous?.uncaughtException(thread, throwable)
            }
            // Ubij główny proces — ekran crashu żyje w osobnym procesie :crash.
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    private fun isCrashProcess(): Boolean {
        val pid = Process.myPid()
        val manager = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        return manager.runningAppProcesses?.firstOrNull { it.pid == pid }
            ?.processName?.endsWith(":crash") == true
    }
}
