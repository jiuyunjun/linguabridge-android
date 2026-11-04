package dev.linguabridge.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.linguabridge.data.FixtureExporter
import dev.linguabridge.domain.CaptureTarget
import dev.linguabridge.data.ProxySettingsStore
import dev.linguabridge.data.api.ProxySettings

/** adb shell-only Debug entry. Manifest requires the privileged DUMP permission. */
class DebugInspectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CONFIGURE_DEMO) {
            try {
                val settings = ProxySettings(intent.getStringExtra("baseUrl").orEmpty(), intent.getStringExtra("deviceToken").orEmpty())
                require(settings.baseUrl == "http://127.0.0.1:8787")
                ProxySettingsStore(context).save(settings)
                FixtureExporter.writeAdbStatus(context, "CONFIGURED")
            } catch (_: Exception) { FixtureExporter.writeAdbStatus(context, "CONFIG_FAILED") }
            return
        }
        if (intent.action != ACTION) return
        val target = try { CaptureTarget(intent.getStringExtra("targetPackage").orEmpty()) } catch (_: Exception) {
            FixtureExporter.writeAdbStatus(context, "INVALID_TARGET")
            return
        }
        val version = intent.getStringExtra("appVersion").orEmpty()
        if (!version.matches(Regex("[A-Za-z0-9._+()-]{1,120}"))) {
            FixtureExporter.writeAdbStatus(context, "INVALID_VERSION")
            return
        }
        val service = InspectorService.instance
        if (service == null) {
            FixtureExporter.writeAdbStatus(context, "SERVICE_NOT_CONNECTED")
            return
        }
        service.inspectFromAdb(target, version, intent.getBooleanExtra("includeUnimportantViews", false))
    }

    companion object {
        const val ACTION = "dev.linguabridge.debug.INSPECT_CONFIRMED_PAGE_ONCE"
        const val CONFIGURE_DEMO = "dev.linguabridge.debug.CONFIGURE_DEMO_PROXY"
    }
}
