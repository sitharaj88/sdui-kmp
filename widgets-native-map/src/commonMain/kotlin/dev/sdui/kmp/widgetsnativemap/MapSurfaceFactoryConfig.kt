package dev.sdui.kmp.widgetsnativemap

/**
 * Static documentation companion for [MapSurfaceFactory] consumers.
 *
 * The Android factory ships with a runtime gate driven by [requireApiKey]: when the host's
 * `AndroidManifest.xml` only carries the placeholder API key (`REPLACE_WITH_YOUR_KEY`) the
 * factory delegates to [MapSurfacePlaceholder] rather than attempting a real Google Maps
 * render — keeping the framework's "client never crashes" invariant intact even when a host
 * registers the factory but has not yet signed up for a Google Cloud key.
 *
 * Wire from the host:
 *
 * ```kotlin
 * NativeSurfaceRegistry.build {
 *     register(MapSurfaceFactory.instance(requireApiKey = true)) // safe default
 * }
 * ```
 *
 * Hosts with a real key set [requireApiKey] to `false` so the factory bypasses the manifest
 * check entirely and lets Google Play services surface its own diagnostics if the key is
 * misconfigured. Tests typically pass `false` and an in-memory fixture surface.
 */
public object MapSurfaceFactoryConfig {
    /**
     * Sentinel string the README documents as "the value to replace with your real key".
     * The Android factory treats this exact string as a missing key and falls back to
     * [MapSurfacePlaceholder]. Keep it in sync with the manifest meta-data and the sample.
     */
    public const val PLACEHOLDER_API_KEY: String = "REPLACE_WITH_YOUR_KEY"

    /** Manifest meta-data attribute name where the Maps SDK reads its API key from. */
    public const val MANIFEST_API_KEY_ATTRIBUTE: String = "com.google.android.geo.API_KEY"
}
