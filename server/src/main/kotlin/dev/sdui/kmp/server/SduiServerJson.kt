package dev.sdui.kmp.server

import dev.sdui.kmp.protocol.SduiJson
import kotlinx.serialization.json.Json

/**
 * [Json] configured for server-side emission. Same options as the client's [SduiJson];
 * re-exported under a server name so server code doesn't need to import from `:protocol`.
 */
public val SduiServerJson: Json = SduiJson
