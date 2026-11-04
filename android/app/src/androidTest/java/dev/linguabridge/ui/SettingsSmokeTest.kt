package dev.linguabridge.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.linguabridge.data.AssistantPrefs
import dev.linguabridge.data.ProxySettingsStore
import dev.linguabridge.data.api.ProxySettings
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.BeforeClass
import org.junit.AfterClass
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSmokeTest {
    companion object {
        private var watchdog: java.util.Timer? = null
        @BeforeClass @JvmStatic fun boundFrameworkWait() {
            watchdog = java.util.Timer("ui-test-watchdog", true).apply {
                schedule(object : java.util.TimerTask() { override fun run() {
                    val stacks = Thread.getAllStackTraces().filter { (thread, stack) ->
                        thread.name != "ui-test-watchdog" && (thread.name == "main" ||
                            stack.any { it.className.startsWith("androidx.test.") || it.className.startsWith("androidx.compose.ui.test.") })
                    }
                    // Stack metadata only; never inspect locals, page text, or credentials.
                    val summary = stacks.entries.joinToString("\n") { (thread, stack) -> thread.name + ": " + stack.take(30).joinToString(" | ") }
                    val data = android.os.Bundle().apply { putString("stream", "UI_TEST_TIMEOUT before completion\n$summary") }
                    InstrumentationRegistry.getInstrumentation().sendStatus(-2, data)
                    InstrumentationRegistry.getInstrumentation().finish(1, data)
                } }, 45000L)
            }
        }
        @AfterClass @JvmStatic fun stopWatchdog() { watchdog?.cancel() }
    }
    private fun step(value: String) { InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("step", value) }) }
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: InspectorActivity
    private fun launchAssistant() {
        // This device stalls in Instrumentation.startActivitySync. Launch only our own
        // Activity via adb's shell identity, then assert its real lifecycle and UI.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n dev.linguabridge.debug/dev.linguabridge.ui.InspectorActivity"
        )).use { it.readBytes() }
        compose.waitUntil(10000) {
            var resumed: InspectorActivity? = null
            instrumentation.runOnMainSync {
                resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<InspectorActivity>().singleOrNull()
            }
            if (resumed != null) activity = resumed!!
            resumed != null
        }
        compose.waitForIdle()
    }
    @Test fun encryptedSettingsRoundTripAndClear() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ProxySettingsStore(context, "instrumentation")
        val syntheticToken = "synthetic-test-token-not-for-deployment-0000"
        try {
            store.save(ProxySettings("https://proxy.example", syntheticToken))
            assertTrue("encrypted token did not round-trip", store.load()?.deviceToken == syntheticToken)
            val saved = context.getSharedPreferences("instrumentation_settings", 0).all.values.joinToString()
            assertFalse("plaintext token persisted", saved.contains(syntheticToken))
            store.clear(); assertNull(store.load())
        } finally { store.clear() }
    }

    @Test fun settingsScreenSavesProxyAndPreferencesAndClears() {
        step("test-body-entered")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ProxySettingsStore(context)
        val prefs = AssistantPrefs(context)
        val original = store.load()
        val token = "synthetic-ui-token-not-for-deployment-00000"
        try {
            store.clear(); prefs.clear()
            launchAssistant()
            step("save-proxy")
            compose.onNodeWithTag("proxy-url").performTextInput("http://127.0.0.1:8787")
            compose.onNodeWithTag("proxy-token").performTextInput(token)
            compose.onNodeWithTag("save-proxy").performClick()
            compose.waitUntil(5000) { store.load()?.deviceToken == token }
            step("preferences")
            compose.onNodeWithTag("translate-en").performScrollTo().performClick()
            compose.onNodeWithTag("reply-ja").performScrollTo().performClick()
            compose.onNodeWithTag("bubble-switch").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals("en", prefs.translateLanguage)
                assertEquals("ja", prefs.replyLanguage)
                assertFalse(prefs.bubbleEnabled)
            }
            step("clear")
            compose.onNodeWithTag("clear-all").performScrollTo().performClick()
            compose.waitUntil(5000) { store.load() == null }
            compose.runOnIdle { assertEquals("zh", prefs.translateLanguage); assertTrue(prefs.bubbleEnabled) }
        } finally {
            prefs.clear()
            if (original != null) store.save(original) else store.clear()
        }
    }
}
