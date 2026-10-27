package com.brightblu.parc.feature.settings.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.brightblu.parc.core.data.ParcPrefs
import com.brightblu.parc.feature.settings.onboarding.AndroidOnboardingIntents
import com.brightblu.parc.feature.settings.onboarding.AndroidOnboardingProbe
import com.brightblu.parc.feature.settings.onboarding.Onboarding
import com.brightblu.parc.feature.settings.onboarding.OnboardingController
import com.brightblu.parc.feature.settings.onboarding.OnboardingStep
import com.brightblu.parc.feature.settings.onboarding.asOnboardingStore
import com.brightblu.parc.model.Skin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Debug-only host for the T18 onboarding screens (DESIGN §5 S13), mirroring the T16 and T17 review
 * activities. The production host is `app/`; this activity exists so each step can be captured on
 * the emulator without a release manifest change. Absent from release builds.
 *
 * `adb shell am start -n com.brightblu.parc/com.brightblu.parc.feature.settings.debug.OnboardingReviewActivity`
 * opens step 1; `--es step battery` opens a named step. The activity renders the flow even when the
 * persisted flag is already set, so every step stays reachable for review. It leaves the flag alone:
 * only the real Next/Done/Skip taps write it.
 */
class OnboardingReviewActivity : ComponentActivity() {

    private lateinit var controller: OnboardingController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        controller = OnboardingController(
            store = ParcPrefs.create(this).asOnboardingStore(),
            probe = AndroidOnboardingProbe.of(this),
            scope = scope,
        )
        intent.getStringExtra(StepExtra)?.let { id ->
            OnboardingStep.byId(id)?.let(controller::goTo)
        }
        setContent {
            // The stateful entry point, so the debug host exercises exactly what a production host runs.
            Onboarding(
                controller = controller,
                skin = Skin.Midnight,
                onOpenTarget = { AndroidOnboardingIntents.open(this, it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from a Settings page: re-read the probes so a step can flip to "Set".
        if (::controller.isInitialized) controller.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(StepExtra)?.let { id ->
            OnboardingStep.byId(id)?.let(controller::goTo)
        }
    }

    companion object {
        const val StepExtra = "step"
    }
}
