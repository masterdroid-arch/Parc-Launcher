package com.brightblu.parc

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * T250: the "New note" share target. It accepts text/plain SEND, hands the text to the launcher and
 * finishes immediately; the note is not stored until the launcher's note editor opens.
 */
class ShareNoteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = NoteShareRules.text(intent?.action, intent?.type, intent?.getCharSequenceExtra(Intent.EXTRA_TEXT))
        if (text != null) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(NoteShareRules.ExtraText, text)
            })
        }
        finish()
    }
}
