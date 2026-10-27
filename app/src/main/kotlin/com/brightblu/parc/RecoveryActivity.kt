package com.brightblu.parc

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** All crash-preference access stays in the main process; SharedPreferences is not multiprocess. */
class RecoveryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recovered = CrashGuard(this).reset()
        val target = if (recovered) MainActivity::class.java else SafeModeActivity::class.java
        startActivity(Intent(this, target)
            .putExtra("recovery_failed", !recovered)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
