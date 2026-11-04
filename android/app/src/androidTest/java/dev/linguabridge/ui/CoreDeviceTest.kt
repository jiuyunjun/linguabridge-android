package dev.linguabridge.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.linguabridge.data.ProxySettingsStore
import dev.linguabridge.data.api.*
import dev.linguabridge.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device storage/network checks do not depend on Espresso or read third-party windows. */
@RunWith(AndroidJUnit4::class)
class CoreDeviceTest {
    @Test fun keystoreEncryptsAndDeletesDeviceToken() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ProxySettingsStore(context, "instrumentation_core")
        val token = "synthetic-storage-only-token-not-a-secret-0000"
        try {
            store.save(ProxySettings("https://proxy.example", token))
            assertTrue("token round-trip failed", store.load()?.deviceToken == token)
            assertFalse("token stored in plaintext", context.getSharedPreferences("instrumentation_core_settings", 0).all.values.joinToString().contains(token))
            store.clear(); assertNull(store.load())
        } finally { store.clear() }
    }

    @Test fun androidClientUsesAuthenticatedGoDemoForBothOperations() = runBlocking {
        val token = InstrumentationRegistry.getArguments().getString("demoToken").orEmpty()
        assumeTrue("explicit demo configuration required", token.matches(Regex("[A-Za-z0-9_-]{32,256}")))
        val settings = ProxySettings("http://127.0.0.1:8787", token)
        val repository = AssistanceRepository(true)
        val page = PageContext.from(PageContext.sampleMail())
        for (operation in AssistOperation.entries) {
            val input = AssistRequest(operation = operation, targetLanguage = "ja", pageType = "email", sourcePackage = page.sourcePackage,
                targetBlockId = if (operation == AssistOperation.TRANSLATE) "b3" else "",
                tone = if (operation == AssistOperation.REPLY) "polite" else "",
                userIntent = if (operation == AssistOperation.REPLY) "Politely follow up next week without inventing a date" else "", blocks = page.blocks)
            val result = repository.generate(settings, input)
            assertTrue((result as? AssistOutcome.Error)?.message ?: "expected authenticated demo result", result is AssistOutcome.Success)
            val value = (result as AssistOutcome.Success).result
            assertTrue("demo label missing", value.demo && value.model == "demo-no-ai")
            if (operation == AssistOperation.TRANSLATE_PAGE) assertEquals(page.blocks.size, value.translations.size)
            else assertTrue("demo text missing", value.text.contains("固定"))
        }
        val invalid = repository.generate(settings.copy(deviceToken = "incorrect-device-token-0000000000000000"),
            AssistRequest(operation = AssistOperation.TRANSLATE, targetLanguage = "ja", pageType = "email", sourcePackage = page.sourcePackage,
                targetBlockId = "b3", userIntent = "", blocks = page.blocks))
        assertTrue("invalid device token accepted", invalid is AssistOutcome.Error)
    }
}
