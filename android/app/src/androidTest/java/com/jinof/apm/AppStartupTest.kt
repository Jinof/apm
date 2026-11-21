package com.jinof.apm

import android.animation.ValueAnimator
import android.graphics.drawable.Animatable
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class AppStartupTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun indicatorRunsUntilTheFirstPageIsLaidOutAndThenFadesAutomatically() {
        val activity = rule.activity
        val splashAtLayout = AtomicBoolean()
        val intermediateFrame = AtomicBoolean()
        lateinit var startup: AppStartup
        rule.runOnUiThread {
            startup = AppStartup(activity) {
                Text("首页已就绪", Modifier.testTag("startup_home_content").onGloballyPositioned {
                    if (activity.findViewById<View>(R.id.startup_loading)?.alpha == 1f) splashAtLayout.set(true)
                })
            }
        }
        rule.waitUntil(5_000) {
            rule.runOnUiThread {
                val ring = activity.findViewById<ProgressBar>(R.id.startup_progress)
                ring.isShown && (ring.indeterminateDrawable as Animatable).isRunning
            }
        }
        rule.runOnUiThread {
            assertEquals("让照片，\n说出自己的故事", activity.findViewById<TextView>(R.id.startup_tagline).text)
            assertNull(activity.findViewById<View>(R.id.apm_page))
            val splash = activity.findViewById<View>(R.id.startup_loading)
            splash.viewTreeObserver.addOnDrawListener {
                if (splash.alpha > 0f && splash.alpha < 1f) intermediateFrame.set(true)
            }
            startup.onCatalogReady()
            assertNotNull(activity.findViewById<View>(R.id.startup_loading))
        }
        rule.onNodeWithTag("startup_home_content").assertIsDisplayed()
        rule.waitUntil(5_000) { rule.runOnUiThread { activity.findViewById<View>(R.id.startup_loading) == null } }
        assertTrue("The opaque splash must cover first layout", splashAtLayout.get())
        if (ValueAnimator.areAnimatorsEnabled()) {
            assertTrue("The fade must draw an intermediate frame", intermediateFrame.get())
        }
    }
}
