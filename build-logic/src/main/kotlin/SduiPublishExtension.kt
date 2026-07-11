package dev.sdui.kmp.buildlogic

import org.gradle.api.provider.Property

/**
 * Per-module overrides for sdui-kmp's `maven-publish` integration.
 *
 * Library modules apply `id("sdui.publish")` and may optionally describe themselves:
 *
 * ```
 * sduiPublish {
 *     description.set("Server DSL for emitting typed, versioned UI trees.")
 * }
 * ```
 *
 * The convention plugin reads [description] when configuring every `MavenPublication`'s POM.
 * If unset, a generic description (`"sdui-kmp module: <project name>"`) is written instead —
 * adequate for `publishToMavenLocal` smoke tests but visibly weak on Maven Central, so every
 * shipping module overrides it.
 */
public interface SduiPublishExtension {
    /** One-sentence human-readable description, surfaced as `<description>` in the POM. */
    public val description: Property<String>
}
