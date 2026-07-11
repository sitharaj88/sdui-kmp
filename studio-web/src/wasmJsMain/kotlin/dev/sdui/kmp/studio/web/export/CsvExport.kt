package dev.sdui.kmp.studio.web.export

import dev.sdui.kmp.studio.web.api.AuditEntry

/**
 * Browser-side CSV download for the Audit tab.
 *
 * The CSV layout mirrors the server's `AuditListItem` exactly so an exported file is
 * round-trippable: `id, screenId, editorId, action, fromVersion, toVersion, at, requestId`.
 * Field values are RFC-4180 quoted (any embedded `"` doubled) so a value containing a comma or
 * a newline survives the round trip into a spreadsheet.
 *
 * The download is triggered with the standard `URL.createObjectURL(Blob(...))` + hidden anchor
 * element + `.click()` pattern. We don't pull in `kotlin-wrappers` for this — a single `js(...)`
 * snippet is the smallest and most predictable way to get bytes onto disk from a wasm app.
 */
public fun downloadCsv(fileName: String, entries: List<AuditEntry>) {
    val csv = buildString {
        append("id,screenId,editorId,action,fromVersion,toVersion,at,requestId\n")
        entries.forEach { entry ->
            append(quote(entry.id))
            append(',')
            append(quote(entry.screenId))
            append(',')
            append(quote(entry.editorId))
            append(',')
            append(quote(entry.action))
            append(',')
            append(entry.fromVersion?.toString().orEmpty())
            append(',')
            append(entry.toVersion?.toString().orEmpty())
            append(',')
            append(quote(entry.at))
            append(',')
            append(quote(entry.requestId))
            append('\n')
        }
    }
    triggerBrowserDownload(fileName = fileName, mimeType = "text/csv;charset=utf-8", payload = csv)
}

private fun quote(value: String): String {
    val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuoting) return value
    return '"' + value.replace("\"", "\"\"") + '"'
}

/**
 * Trigger a browser file download for [payload]. Implemented as a single `js(...)` snippet so
 * the call survives whatever shape the kotlinx-browser Blob/URL bindings take across versions.
 */
private fun triggerBrowserDownload(fileName: String, mimeType: String, payload: String) {
    triggerDownloadJs(fileName = fileName, mimeType = mimeType, payload = payload)
}

@Suppress("UnusedParameter") // Parameters are referenced inside the js(...) body below.
private fun triggerDownloadJs(fileName: String, mimeType: String, payload: String): Unit = js(
    """
    (function() {
        var blob = new Blob([payload], { type: mimeType });
        var url = URL.createObjectURL(blob);
        var anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = fileName;
        anchor.style.display = 'none';
        document.body.appendChild(anchor);
        anchor.click();
        document.body.removeChild(anchor);
        URL.revokeObjectURL(url);
    })()
    """,
)
