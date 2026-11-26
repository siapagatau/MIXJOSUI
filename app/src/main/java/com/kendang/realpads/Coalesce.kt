package com.kendang.realpads

import android.os.Handler
import android.os.Looper

// ---------------------------------------------------------------------------------------------------------------------
// Penggabung kerja berulang. Menggeser fader/knob memanggil setter puluhan kali per detik; kerja berat di belakangnya (simpan ke
// SharedPreferences, bangun JSON setelan lalu evaluateJavascript ke WebView YouTube, siarkan ke jendela lain) cukup dilakukan
// beberapa kali per detik dengan NILAI TERBARU. Tanpa ini main thread (yang juga dipakai WebView YouTube) kebanjiran -> fader berat
// dan video/suara YouTube patah-patah.
//   debounce: jalankan SEKALI setelah delayMs sejak panggilan TERAKHIR (cocok untuk simpan ke disk).
//   throttle: jalankan paling sering tiap intervalMs; blok TERAKHIR yang dipakai (cocok untuk kirim ke mesin audio/halaman).
// Semua blok berjalan di main thread.
// ---------------------------------------------------------------------------------------------------------------------
object Coalesce {
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val debounced = HashMap<String, Runnable>()
    private val throttled = HashMap<String, () -> Unit>()

    fun debounce(key: String, delayMs: Long, block: () -> Unit) {
        val r = Runnable {
            synchronized(lock) { debounced.remove(key) }
            block()
        }
        synchronized(lock) {
            debounced.put(key, r)?.let { handler.removeCallbacks(it) }
        }
        handler.postDelayed(r, delayMs)
    }

    fun throttle(key: String, intervalMs: Long, block: () -> Unit) {
        val first: Boolean
        synchronized(lock) {
            first = !throttled.containsKey(key)
            throttled[key] = block
        }
        if (!first) return
        handler.postDelayed({
            val b = synchronized(lock) { throttled.remove(key) }
            b?.invoke()
        }, intervalMs)
    }
}
