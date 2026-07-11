package dev.sdui.kmp.studio.server.routes

/**
 * Drafts admin surface.
 *
 * Per-screen draft GET / PUT routes are mounted by [installScreensAdminRoutes] under
 * `/admin/screens/{id}/draft` because they live in the same lifecycle as the screen they edit
 * — splitting them into a separate router would break that locality.
 *
 * This file is the home for *cross-screen* draft routes (e.g. `GET /admin/drafts` showing every
 * draft owned by the calling editor) which we forward-declare for the Studio UI but defer for
 * S3. Keep the placeholder so the package layout stays stable.
 */
public object DraftsAdminRoute {
    /** Marker token confirming the routes file participated in the studio module wiring. */
    public const val MOUNTED: Boolean = true
}
