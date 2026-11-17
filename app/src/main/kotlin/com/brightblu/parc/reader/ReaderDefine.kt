package com.brightblu.parc.reader

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * T256: Define sends the selected word through Android's "process text" action. The phone's
 * dictionary app (or any other process-text app, when no dictionary-looking one exists) receives
 * the word only when this action is tapped. Parc adds no dictionary and no online service.
 */
internal object ReaderDefine {
    private const val Action = "android.intent.action.PROCESS_TEXT"
    private const val MimeType = "text/plain"

    private fun baseIntent(text: String): Intent = Intent(Action)
        .addCategory(Intent.CATEGORY_DEFAULT)
        .setType(MimeType)
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

    /** Returns false when no installed app can process the word; the menu then shows [ReaderDefineRules.NoAppMessage]. */
    fun open(context: Context, text: String): Boolean {
        val pm = context.packageManager
        val base = baseIntent(text)
        val resolved = pm.queryIntentActivities(
            base,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
        val handlers = resolved.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(activity.packageName)
            DefineHandler(
                packageName = activity.packageName,
                activityName = activity.name,
                label = label.ifBlank { activity.packageName },
                dictionary = ReaderDefineRules.looksLikeDictionary(label, activity.packageName),
            )
        }
        val plan = ReaderDefineRules.plan(handlers)
        if (!plan.hasApp) return false
        val send = when {
            plan.wantsChooser -> {
                val excluded = plan.all.filterNot { candidate -> candidate in plan.chosen }
                Intent.createChooser(base, "Define").apply {
                    if (excluded.isNotEmpty()) {
                        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                            excluded.map { ComponentName(it.packageName, it.activityName) }.toTypedArray())
                    }
                }
            }
            else -> {
                val one = plan.chosen.single()
                Intent(base).setComponent(ComponentName(one.packageName, one.activityName))
            }
        }
        if (context !is Activity) send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(send); true }.getOrDefault(false)
    }
}
