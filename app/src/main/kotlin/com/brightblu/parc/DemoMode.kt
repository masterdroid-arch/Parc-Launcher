package com.brightblu.parc

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import com.brightblu.parc.core.data.DemoNamespace
import java.io.File

/** Read once in attachBaseContext. Changing the marker requires a process restart. */
internal object DemoMode {
    const val MarkerName = "demo.enabled"
    var enabled: Boolean = false
        private set

    fun attach(base: Context): Context {
        enabled = allowed(BuildConfig.DEBUG, Build.FINGERPRINT, Build.MODEL, Build.HARDWARE) &&
            base.getExternalFilesDir(null)?.let { File(it, MarkerName).isFile } == true
        if (!enabled) return base
        return DemoPreferencesContext(DemoNamespace.wrap(base, true))
    }

    fun allowed(debug: Boolean, fingerprint: String, model: String, hardware: String): Boolean =
        debug && (fingerprint.startsWith("generic") || fingerprint.startsWith("google/sdk_gphone")) &&
            (model.startsWith("sdk_gphone") || model == "Android SDK built for arm64" ||
                model == "Android SDK built for x86") &&
            hardware in setOf("ranchu", "goldfish")

    fun preferenceName(name: String): String = "${DemoNamespace.DirectoryName}_$name"
}

/** Android's SharedPreferences API delegates to ContextImpl, so isolate names explicitly. */
private class DemoPreferencesContext(base: Context) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences(DemoMode.preferenceName(name), mode)
    override fun deleteSharedPreferences(name: String): Boolean =
        super.deleteSharedPreferences(DemoMode.preferenceName(name))
}
