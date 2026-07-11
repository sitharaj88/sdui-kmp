package dev.sdui.kmp.protocol

/**
 * Maximum nesting depth the runtime traverses when rendering or patching a [UiNode] tree.
 *
 * A server-driven tree crosses a network boundary, so its depth is untrusted input. Recursive
 * rendering and recursive tree patching both consume one stack frame per level; a pathologically
 * (or maliciously) deep tree would otherwise overflow the stack and crash the client, violating
 * the "client never crashes" invariant.
 *
 * The budget is the same for both the initial render and any [TreePatch]-mutated tree so that the
 * two paths agree on which nodes are reachable. Beyond this depth the runtime renders the offending
 * node's [UiNode.fallback] (or nothing) and reports telemetry instead of descending further; it
 * never throws.
 *
 * `128` is generous for real UIs — legitimate layouts rarely nest past a few dozen levels — while
 * staying well inside the smallest platform stacks (Kotlin/Native, Wasm). Hosts that need a
 * different ceiling can override it at the runtime layer without changing the wire contract.
 */
public const val MAX_UI_TREE_DEPTH: Int = 128
