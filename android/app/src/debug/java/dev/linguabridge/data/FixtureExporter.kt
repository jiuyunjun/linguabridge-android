package dev.linguabridge.data

import android.content.Context
import android.os.Build
import dev.linguabridge.domain.UiSnapshot
import dev.linguabridge.domain.redacted
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object FixtureExporter {
    fun export(context: Context, snapshot: UiSnapshot, replacements: Map<String, String>,
               appVersion: String, caseLabel: String): File {
        require(appVersion.isNotBlank() && caseLabel.isNotBlank())
        val safe = snapshot.redacted(replacements)
        val json = JSONObject().apply {
            put("schemaVersion", 6)
            put("targetPackage", safe.sourcePackage)
            put("sourceType", safe.source.name.lowercase())
            put("captureId", safe.captureId)
            put("inspectorVersion", "0.3-page-assistant")
            put("compatibility", "UNVERIFIED")
            put("kind", "redacted-debug-page-tree-not-messages")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidSdk", Build.VERSION.SDK_INT)
            put("appVersion", appVersion.take(120))
            put("caseLabel", caseLabel.take(120))
            put("capturedAtEpochMillis", safe.capturedAtEpochMillis)
            put("windowId", safe.windowId)
            put("truncated", safe.truncated)
            put("diagnostics", JSONObject().apply {
                put("visitedNodes", safe.diagnostics.visitedNodes)
                put("invisibleNodesSkipped", safe.diagnostics.invisibleNodesSkipped)
                put("childRequests", safe.diagnostics.childRequests)
                put("nullChildren", safe.diagnostics.nullChildren)
                put("serviceFlags", safe.diagnostics.serviceFlags ?: JSONObject.NULL)
                put("serviceCapabilities", safe.diagnostics.serviceCapabilities ?: JSONObject.NULL)
                put("rootProbes", JSONArray().apply {
                    safe.diagnostics.rootProbes.forEach { probe -> put(JSONObject().apply {
                        put("elapsedMillis", probe.elapsedMillis)
                        put("cacheCleared", probe.cacheCleared ?: JSONObject.NULL)
                        put("windowId", probe.windowId)
                        put("visible", probe.visible)
                        put("sensitiveReason", probe.sensitiveReason?.name ?: JSONObject.NULL)
                        put("childCount", probe.childCount ?: JSONObject.NULL)
                        put("className", probe.className ?: JSONObject.NULL)
                    }) }
                })
                put("rootExclusion", safe.diagnostics.rootExclusion?.name ?: JSONObject.NULL)
                put("excludedSubtrees", JSONObject().apply {
                    safe.diagnostics.excludedSubtrees.forEach { (reason, count) -> put(reason.name, count) }
                })
            })
            put("nodes", JSONArray().apply {
                safe.nodes.forEach { n -> put(JSONObject().apply {
                    put("path", n.path); put("parentPath", n.parentPath ?: JSONObject.NULL)
                    put("className", n.className ?: JSONObject.NULL)
                    put("resourceId", n.resourceId ?: JSONObject.NULL)
                    put("bounds", JSONArray(listOf(n.bounds.left, n.bounds.top, n.bounds.right, n.bounds.bottom)))
                    put("text", n.text ?: JSONObject.NULL)
                    put("description", n.description ?: JSONObject.NULL)
                    put("sender", "UNKNOWN")
                }) }
            })
        }
        val directory = File(context.filesDir, "fixtures").apply { mkdirs() }
        val target = File(directory, "m0-fixture.json")
        val temporary = File(directory, "m0-fixture.tmp")
        temporary.writeText(json.toString(2), Charsets.UTF_8)
        check(temporary.renameTo(target)) { "Export failed" }
        return target
    }

    fun clear(context: Context) {
        val directory = File(context.filesDir, "fixtures")
        listOf("m0-fixture.json", "m0-fixture.tmp", "adb-status.txt").forEach { name ->
            val file = File(directory, name)
            check(!file.exists() || file.delete()) { "Delete failed" }
        }
    }

    fun writeAdbStatus(context: Context, status: String) {
        require(status.matches(Regex("[A-Z_]+")))
        val directory = File(context.filesDir, "fixtures").apply { mkdirs() }
        File(directory, "adb-status.txt").writeText(status, Charsets.US_ASCII)
    }
}
