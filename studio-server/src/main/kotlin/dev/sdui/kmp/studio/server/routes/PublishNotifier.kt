package dev.sdui.kmp.studio.server.routes

/**
 * Forward-declared seam for the live-publish hook. Wired into
 * [dev.sdui.kmp.studio.server.StudioModule.studioModule] so a future S3 milestone can plug in
 * a `WebSocketLivePublisher` (broadcasting `LiveEvent.TreeUpdate` over the existing
 * `:transport-live` socket) without touching the publish route.
 *
 * In this milestone the default no-op is wired; clients hot-reloading on publish is intentionally
 * deferred. Tests that need to assert publish-side fan-out can still pass a recording stub.
 */
public fun interface PublishNotifier {
    /**
     * Called from the publish route after a new version is committed and the audit row is
     * written. [body] is the canonical JSON-serialised Screen — the same string that lives in
     * `screen_versions.body_json`.
     *
     * Implementations MUST NOT throw — a notifier failure must never roll back the publish.
     */
    public suspend fun screenPublished(screenId: String, version: Int, body: String)
}

/** Singleton no-op notifier used as the default. */
public val NoopPublishNotifier: PublishNotifier = PublishNotifier { _, _, _ -> }
