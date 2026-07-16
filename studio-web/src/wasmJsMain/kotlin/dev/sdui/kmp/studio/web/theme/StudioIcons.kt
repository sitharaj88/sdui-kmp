package dev.sdui.kmp.studio.web.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Hand-built icon set for the Studio. Path data is transcribed from the Apache-2.0
 * Material Design icon set (24×24 viewport) and parsed with [addPathNodes]; each icon is
 * ≈0.5 KB of source, so the whole set costs a fraction of the `materialIconsExtended`
 * dependency that `MainShell` deliberately avoids for wasm bundle size (this file is that
 * comment's "lightweight inline SVGs" plan of record).
 *
 * All icons are `by lazy` so unused ones cost nothing at boot. Render at 14–20 dp via
 * `Icon(StudioIcons.X, contentDescription = …)`; tinting flows from `LocalContentColor`.
 */
internal object StudioIcons {

    // -- Navigation rail / chrome ---------------------------------------------------------

    /** Layers glyph — Screens tab. */
    val Screens: ImageVector by lazy {
        icon(
            "Screens",
            "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.26 -7.38,5.73zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 " +
                "1.63,1.27L12,16z",
        )
    }

    /** Pencil glyph — Drafts tab and draft affordances. */
    val Drafts: ImageVector by lazy {
        icon(
            "Drafts",
            "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34," +
                "-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z",
        )
    }

    /** History clock — Audit tab, revert affordances. */
    val Audit: ImageVector by lazy {
        icon(
            "Audit",
            "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 " +
                "-7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s" +
                "-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8H12z",
        )
    }

    /** Flask — Experiments tab. */
    val Experiments: ImageVector by lazy {
        icon(
            "Experiments",
            "M19.8,18.4L14,10.67V6.5l1.35,-1.69C15.61,4.48 15.38,4 14.96,4H9.04c-0.42,0 -0.65,0.48 -0.39,0.81" +
                "L10,6.5v4.17L4.2,18.4c-0.49,0.66 -0.02,1.6 0.8,1.6h14c0.82,0 1.29,-0.94 0.8,-1.6z",
        )
    }

    /** Left arrow — back-to-list buttons. */
    val ArrowBack: ImageVector by lazy {
        icon("ArrowBack", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    }

    /** Right chevron — list-row affordance. */
    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z")
    }

    /** Down chevron — expanded tree rows, dropdowns. */
    val ChevronDown: ImageVector by lazy {
        icon("ChevronDown", "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z")
    }

    /** Person bust — account menu. */
    val Person: ImageVector by lazy {
        icon(
            "Person",
            "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2" +
                "h16v-2c0,-2.66 -5.33,-4 -8,-4z",
        )
    }

    /** Door-with-arrow — sign out. */
    val Logout: ImageVector by lazy {
        icon(
            "Logout",
            "M17,7l-1.41,1.41L18.17,11H8v2h10.17l-2.58,2.58L17,17l5,-5zM4,5h8V3H4c-1.1,0 -2,0.9 -2,2v14c0,1.1 " +
                "0.9,2 2,2h8v-2H4V5z",
        )
    }

    // -- Toolbar actions ------------------------------------------------------------------

    /** Floppy — Save draft. */
    val Save: ImageVector by lazy {
        icon(
            "Save",
            "M17,3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V7l-4,-4zM12,19c-1.66,0 -3," +
                "-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM15,9H5V5h10v4z",
        )
    }

    /** Cloud-upload — Publish. */
    val Publish: ImageVector by lazy {
        icon(
            "Publish",
            "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 " +
                "2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96zM14,13v4h-4v-4H7l5,-5 5,5h-3z",
        )
    }

    /** Undo arc. */
    val Undo: ImageVector by lazy {
        icon(
            "Undo",
            "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 " +
                "6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
        )
    }

    /** Redo arc. */
    val Redo: ImageVector by lazy {
        icon(
            "Redo",
            "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 " +
                "7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z",
        )
    }

    /** Circular arrows — retry. */
    val Refresh: ImageVector by lazy {
        icon(
            "Refresh",
            "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 " +
                "7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 " +
                "4.22,1.78L13,11h7V4l-2.35,2.35z",
        )
    }

    /** Down-into-tray — CSV export. */
    val Download: ImageVector by lazy {
        icon("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    }

    /** Trash can — delete/remove. */
    val Delete: ImageVector by lazy {
        icon(
            "Delete",
            "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z",
        )
    }

    /** Plus. */
    val Add: ImageVector by lazy {
        icon("Add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    }

    /** Two-sheet copy — duplicate node. */
    val Copy: ImageVector by lazy {
        icon(
            "Copy",
            "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 " +
                "2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z",
        )
    }

    /** Upward arrow — move node earlier among its siblings. */
    val MoveUp: ImageVector by lazy {
        icon("MoveUp", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")
    }

    /** Downward arrow — move node later among its siblings. */
    val MoveDown: ImageVector by lazy {
        icon("MoveDown", "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z")
    }

    /** X — close/clear. */
    val Close: ImageVector by lazy {
        icon(
            "Close",
            "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 " +
                "13.41,12z",
        )
    }

    /** Magnifier — palette filter. */
    val Search: ImageVector by lazy {
        icon(
            "Search",
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91," +
                "16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 " +
                "5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
        )
    }

    // -- Mode toggles / status ------------------------------------------------------------

    /** `</>` — JSON mode. */
    val Code: ImageVector by lazy {
        icon(
            "Code",
            "M9.4,16.6L4.8,12l4.6,-4.6L8,6l-6,6 6,6 1.4,-1.4zM14.6,16.6l4.6,-4.6 -4.6,-4.6L16,6l6,6 -6,6 " +
                "-1.4,-1.4z",
        )
    }

    /** Eye — Visual mode / preview. */
    val Eye: ImageVector by lazy {
        icon(
            "Eye",
            "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11," +
                "-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 " +
                "-3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
        )
    }

    /** Check in a circle — success status. */
    val CheckCircle: ImageVector by lazy {
        icon(
            "CheckCircle",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10," +
                "14.17l7.59,-7.59L19,8l-9,9z",
        )
    }

    /** Exclamation in a circle — errors. */
    val ErrorCircle: ImageVector by lazy {
        icon(
            "ErrorCircle",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-2h2v2zM13,13h-2" +
                "V7h2v6z",
        )
    }

    /** Inbox tray — empty states. */
    val Inbox: ImageVector by lazy {
        icon(
            "Inbox",
            "M19,3H4.99c-1.11,0 -1.98,0.89 -1.98,2L3,19c0,1.1 0.88,2 1.99,2H19c1.1,0 2,-0.9 2,-2V5c0,-1.11 " +
                "-0.9,-2 -2,-2zM19,15h-4c0,1.66 -1.35,3 -3,3s-3,-1.34 -3,-3H4.99V5H19v10z",
        )
    }

    /** Crescent — dark preview toggle. */
    val Moon: ImageVector by lazy {
        icon(
            "Moon",
            "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.46,0 0.9,-0.04 1.34,-0.1 -2.83,-1.65 -4.72,-4.71 -4.72," +
                "-8.22 0,-3.51 1.89,-6.57 4.72,-8.22C12.9,3.04 12.46,3 12,3z",
        )
    }

    /** Sun with rays — light preview toggle. */
    val Sun: ImageVector by lazy {
        icon(
            "Sun",
            "M12,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5 5,-2.24 5,-5 -2.24,-5 -5,-5zM2,13h2v-2H2v2zM20,13h2v-2h-2" +
                "v2zM11,2h2v2h-2V2zM11,20h2v2h-2v-2zM5.99,4.58L4.58,5.99l1.41,1.41L7.4,5.99 5.99,4.58zM18.01," +
                "4.58l-1.41,1.41 1.41,1.41 1.41,-1.41 -1.41,-1.41zM16.6,18.01l1.41,1.41 1.41,-1.41 -1.41,-1.41 " +
                "-1.41,1.41zM4.58,18.01l1.41,1.41 1.41,-1.41 -1.41,-1.41 -1.41,1.41z",
        )
    }

    // -- Device presets (preview / canvas frame picker) -----------------------------------

    /** Smartphone outline — Phone device preset. */
    val DevicePhone: ImageVector by lazy {
        icon(
            "DevicePhone",
            "M17,1.01L7,1C5.9,1 5,1.9 5,3v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 " +
                "-2,-1.99zM17,19H7V5h10v14z",
        )
    }

    /** Landscape tablet outline — Tablet device preset. */
    val DeviceTablet: ImageVector by lazy {
        icon(
            "DeviceTablet",
            "M21,4H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2z" +
                "M21,18H3V6h18v12z",
        )
    }

    /** Monitor with stand — Desktop device preset. */
    val DeviceDesktop: ImageVector by lazy {
        icon(
            "DeviceDesktop",
            "M21,2H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h7v2H8v2h8v-2h-2v-2h7c1.1,0 2,-0.9 2,-2V4c0," +
                "-1.1 -0.9,-2 -2,-2zM21,16H3V4h18v12z",
        )
    }

    // -- Widget-type glyphs (palette, layers panel) ----------------------------------------

    /** Stacked rows — Column container. */
    val NodeColumn: ImageVector by lazy {
        icon(
            "NodeColumn",
            "M20,13H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1v-6c0,-0.55 -0.45,-1 " +
                "-1,-1zM20,3H4C3.45,3 3,3.45 3,4v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1V4c0,-0.55 -0.45," +
                "-1 -1,-1z",
        )
    }

    /** "T" glyphs — Text node. */
    val NodeText: ImageVector by lazy {
        icon("NodeText", "M2.5,4v3h5v12h3V7h5V4h-13zM21.5,9h-9v3h3v7h3v-7h3V9z")
    }

    /** Button-with-sparkle — Button node. */
    val NodeButton: ImageVector by lazy {
        icon(
            "NodeButton",
            "M22,9v6c0,1.1 -0.9,2 -2,2h-1v-2h1V9H4v6h6v2H4c-1.1,0 -2,-0.9 -2,-2V9c0,-1.1 0.9,-2 2,-2h16c1.1," +
                "0 2,0.9 2,2zM14.5,19l1.09,-2.41L18,15.5l-2.41,-1.09L14.5,12l-1.09,2.41L11,15.5l2.41,1.09L14.5,19z",
        )
    }

    /** Input box with arrow — TextField node. */
    val NodeTextField: ImageVector by lazy {
        icon(
            "NodeTextField",
            "M21,3.01H3c-1.1,0 -2,0.9 -2,2V9h2V4.99h18v14.03H3V15H1v4.01c0,1.1 0.9,1.98 2,1.98h18c1.1,0 2," +
                "-0.88 2,-1.98v-14c0,-1.11 -0.9,-1.99 -2,-1.99zM11,16l4,-4 -4,-4v3H1v2h10v3z",
        )
    }

    /** Checked box — Checkbox node. */
    val NodeCheckbox: ImageVector by lazy {
        icon(
            "NodeCheckbox",
            "M19,3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.11,0 2,-0.9 2,-2V5c0,-1.1 -0.89,-2 -2,-2z" +
                "M10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z",
        )
    }

    /** Mountain photo — Image / AsyncImage nodes. */
    val NodeImage: ImageVector by lazy {
        icon(
            "NodeImage",
            "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2z" +
                "M8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
        )
    }

    /** Row list — LazyList node. */
    val NodeList: ImageVector by lazy {
        icon(
            "NodeList",
            "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z",
        )
    }

    /** Folded map — NavHost node. */
    val NodeNav: ImageVector by lazy {
        icon(
            "NodeNav",
            "M20.5,3l-0.16,0.03L15,5.1 9,3 3.36,4.9c-0.21,0.07 -0.36,0.25 -0.36,0.48V20.5c0,0.28 0.22,0.5 " +
                "0.5,0.5l0.16,-0.03L9,18.9l6,2.1 5.64,-1.9c0.21,-0.07 0.36,-0.25 0.36,-0.48V3.5c0,-0.28 -0.22," +
                "-0.5 -0.5,-0.5zM15,19l-6,-2.11V5l6,2.11V19z",
        )
    }

    /** Widget tiles — NativeSurface node. */
    val NodeNative: ImageVector by lazy {
        icon(
            "NodeNative",
            "M13,13v8h8v-8h-8zM3,21h8v-8H3v8zM3,3v8h8V3H3zM16.66,1.69L11,7.34 16.66,13 22.31,7.34 16.66,1.69z",
        )
    }

    /** Question mark in a circle — unknown node types. */
    val NodeUnknown: ImageVector by lazy {
        icon(
            "NodeUnknown",
            "M11,18h2v-2h-2v2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c" +
                "-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8zM12,6c-2.21,0 -4,1.79 -4,4h2c0," +
                "-1.1 0.9,-2 2,-2s2,0.9 2,2c0,2 -3,1.75 -3,5h2c0,-2.25 3,-2.5 3,-5 0,-2.21 -1.79,-4 -4,-4z",
        )
    }

    /** Padlock — secure TextField badge. */
    val Lock: ImageVector by lazy {
        icon(
            "Lock",
            "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1," +
                "0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9," +
                "2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z",
        )
    }
}

/** Builds one 24×24 material-style [ImageVector] from SVG path data. */
private fun icon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = ICON_DEFAULT_DP.dp,
        defaultHeight = ICON_DEFAULT_DP.dp,
        viewportWidth = ICON_VIEWPORT,
        viewportHeight = ICON_VIEWPORT,
    ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()

private const val ICON_DEFAULT_DP = 24
private const val ICON_VIEWPORT = 24f
