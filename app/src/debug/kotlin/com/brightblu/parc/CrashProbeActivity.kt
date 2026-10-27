package com.brightblu.parc

import android.app.Activity
import android.os.Bundle
import android.os.Handler

/** Debug-only probe exercises the real uncaught-exception handler and process death. */
class CrashProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Remove this probe from the task before crashing so Android cannot relaunch it.
        finish()
        Handler(mainLooper).post { error("T03 forced crash probe") }
    }
}
