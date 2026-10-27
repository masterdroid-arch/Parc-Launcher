package com.brightblu.parc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brightblu.parc.core.data.LauncherAppIndex
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Independent plain app list: no ring renderer, persistence graph, search or animation. */
class SafeModeActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var index: LauncherAppIndex? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appsIndex = LauncherAppIndex.create(applicationContext, scope)
        index = appsIndex
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            val skin = Skin.Midnight
            val apps by appsIndex.apps.collectAsState()
            val text = TextStyle(color = Color(skin.colors.textPrimary.toInt()), fontSize = skin.typography.menu.sizeSp.sp)
            val error = intent.getBooleanExtra("recovery_failed", false)
            Column(Modifier.fillMaxSize().background(Color(skin.colors.bg.toInt())).systemBarsPadding()
                .padding(horizontal = Metrics.textGutterDp.dp)) {
                BasicText("Safe mode", Modifier.padding(vertical = Metrics.gridDp.dp * 2),
                    style = text.copy(fontSize = skin.typography.title.sizeSp.sp))
                BasicText("Parc stopped repeatedly. You can still open your apps.", style = text)
                BasicText("Exit safe mode", Modifier.fillMaxWidth()
                    .clickable(role = Role.Button) {
                        startActivity(Intent(this@SafeModeActivity, RecoveryActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finishAndRemoveTask()
                    }.heightIn(min = Metrics.touchTargetDp.dp).padding(vertical = Metrics.gridDp.dp * 3), style = text)
                if (error) BasicText("Could not reset recovery state. Try again.", style = text)
                // OPUS6 (audit F5, F6): ways out without clearing Parc's data. Each asks once more
                // before acting, and moves the files aside instead of deleting them.
                var confirm by remember { mutableStateOf<String?>(null) }
                var result by remember { mutableStateOf("") }
                val dataDir = java.io.File(applicationInfo.dataDir)
                listOf(
                    Triple("settings", "Reset Parc settings (keeps your layout, notes and Focus modes)", "Tap again to reset settings"),
                    Triple("layout", "Reset home layout (keeps a copy)", "Tap again to reset the layout"),
                ).forEach { (key, label, again) ->
                    BasicText(if (confirm == key) again else label, Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) {
                            if (confirm != key) { confirm = key; return@clickable }
                            confirm = null
                            result = try {
                                val now = System.currentTimeMillis()
                                val moved = if (key == "settings") SafeModeRecovery.resetSettings(dataDir, now)
                                    else SafeModeRecovery.resetLayout(dataDir, now)
                                if (moved == null) "Nothing to reset." else "Done. The old files were kept. Tap Exit safe mode."
                            } catch (e: Exception) {
                                "Could not reset. Nothing was changed."
                            }
                        }.heightIn(min = Metrics.touchTargetDp.dp).padding(vertical = Metrics.gridDp.dp * 3), style = text)
                }
                if (result.isNotEmpty()) BasicText(result, style = text)
                LazyColumn(Modifier.weight(1f)) {
                    if (apps.isEmpty()) item { BasicText("Loading apps…", style = text) }
                    items(apps, key = { it.id.value }) { app ->
                        BasicText(app.label, Modifier.fillMaxWidth().clickable(enabled = !app.suspended, role = Role.Button) {
                            appsIndex.launch(app.id)
                        }.heightIn(min = Metrics.touchTargetDp.dp).padding(vertical = Metrics.gridDp.dp * 3), style = text)
                    }
                }
            }
        }
        configureParcWindow()
    }

    override fun onDestroy() {
        index?.close()
        scope.cancel()
        super.onDestroy()
    }
}
