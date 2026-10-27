package com.brightblu.parc

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.*
import kotlinx.coroutines.delay
import java.io.File

/** App-local semantics/provider verification; never binds, enables or suppresses any system service. */
class AccessibilityProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val geometry = remember(maxWidth, maxHeight) { RingLayout(maxWidth.value, maxHeight.value) }
                val home = remember(geometry) {
                    RingScenes.home(geometry, geometry.arcs.flatMap { arc ->
                        List(arc.slotCapacity) { index ->
                            val label = "${arc.id} slot $index"
                            RingSlot(SlotPosition(arc.id, index), if (arc.index < 2) Slot.App(AppId(label))
                                else Slot.Folder(FolderId(label)), label, unreadCount = index)
                        }
                    }.reversed(), "09:41")
                }
                var target by remember { mutableStateOf(home) }
                var action by remember { mutableStateOf("") }
                val callbacks = remember {
                    object : RingCallbacks {
                        override fun onTap(hit: SlotHit) { action = "tap ${hit.position.arcId}/${hit.position.index}" }
                        override fun onLongPress(hit: SlotHit) { action = "menu ${hit.position.arcId}/${hit.position.index}" }
                        override fun onHubTap() { action = "hub" }
                        override fun onSearch() { action = "search" }
                        override fun onDrag(hit: SlotHit, phase: DragPhase) = Unit
                    }
                }
                val renderer = remember { CanvasRingRenderer() }
                renderer.Field(target, Skin.Midnight, callbacks)
                val root = LocalView.current
                LaunchedEffect(home) {
                    val report = StringBuilder()
                    try {
                        delay(1200)
                        val view = composeView(root) ?: error("Compose owner missing")
                        // Debug-only reflection isolates the probe from Compose's internal View type.
                        view.javaClass.getMethod("forceAccessibilityForTesting", Boolean::class.javaPrimitiveType)
                            .invoke(view, true)
                        val owner = view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner
                        fun nodes(): List<SemanticsNode> {
                            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
                            return flatten(owner.unmergedRootSemanticsNode).filter {
                                it.config.getOrNull(SemanticsProperties.ContentDescription) != null
                            }.sortedBy { it.config.getOrNull(SemanticsProperties.TraversalIndex) ?: 0f }
                        }
                        val provider = requireNotNull(view.accessibilityNodeProvider)
                        val all = nodes()
                        check(all.size == home.slots.size + 2)
                        val expected = geometry.arcs.flatMap { arc -> (0 until arc.slotCapacity).map { "${arc.id} slot $it" } }
                        all.forEachIndexed { index, node ->
                            val info = requireNotNull(provider.createAccessibilityNodeInfo(node.id))
                            report.appendLine("$index ${info.contentDescription} bounds=${node.boundsInRoot} actions=${info.actionList.map { it.label ?: it.id }}")
                            check(info.isVisibleToUser)
                            check(node.boundsInRoot.width >= Metrics.menuRowHeightDp * resources.displayMetrics.density - 1)
                            check(node.boundsInRoot.height >= Metrics.menuRowHeightDp * resources.displayMetrics.density - 1)
                            if (index < expected.size) check(info.contentDescription.toString().startsWith(expected[index] + ","))
                        }
                        // With system accessibility off, Android rejects provider click events.
                        // Disable the app-local export flag and exercise the same semantic handlers directly.
                        view.javaClass.getMethod("forceAccessibilityForTesting", Boolean::class.javaPrimitiveType)
                            .invoke(view, false)
                        fun click(node: SemanticsNode): Boolean = node.config[SemanticsActions.OnClick].action?.invoke() == true
                        val first = all.first()
                        check(click(first))
                        check(action == "tap A1/0")
                        report.appendLine("PASS semantic click -> $action")
                        val menu = first.config[SemanticsActions.CustomActions].first { it.label == "Show menu" }
                        check(menu.action())
                        check(action == "menu A1/0")
                        report.appendLine("PASS semantic custom action -> $action")
                        target = home.copy(searchOpen = true)
                        delay(300)
                        check(nodes().isEmpty())
                        report.appendLine("PASS search hides underlying nodes")
                        target = home.copy(accessibilityHidden = true)
                        delay(300)
                        check(nodes().isEmpty())
                        report.appendLine("PASS modal hides underlying nodes")
                        target = home
                        delay(300)
                        val restored = nodes()
                        check(restored.size == all.size)
                        check(restored.take(home.slots.size).map { it.config[SemanticsProperties.ContentDescription] } ==
                            all.take(home.slots.size).map { it.config[SemanticsProperties.ContentDescription] })
                        check(click(restored.last()))
                        check(action == "search")
                        report.appendLine("PASS restored nodes and search action")
                        check(click(restored[restored.lastIndex - 1]))
                        check(action == "hub")
                        report.appendLine("PASS hub action")
                        report.appendLine("PASS all app-local checks; spoken TalkBack traversal not measured")
                    } catch (error: Exception) {
                        report.appendLine("FAIL ${error.stackTraceToString()}")
                    }
                    File(filesDir, "accessibility-probe.txt").writeText(report.toString())
                }
            }
        }
        configureParcWindow()
    }

    private fun composeView(root: View): View? {
        if (root.javaClass.methods.any { it.name == "getSemanticsOwner" }) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) composeView(root.getChildAt(i))?.let { return it }
        return null
    }
}
