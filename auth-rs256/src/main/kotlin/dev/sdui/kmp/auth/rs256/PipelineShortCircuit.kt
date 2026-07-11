package dev.sdui.kmp.auth.rs256

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.PipelineCall
import io.ktor.util.pipeline.PipelineContext

/**
 * Handler installed by [ShortCircuitPhase]. It runs with the [PipelineContext] as receiver — so
 * it can call [PipelineContext.finish] to halt the pipeline — and the [ApplicationCall] as its
 * argument.
 */
internal typealias ShortCircuitHandler =
    suspend PipelineContext<Unit, PipelineCall>.(ApplicationCall) -> Unit

/**
 * Ktor plugin [Hook] that runs its handler in the routing pipeline's `Plugins` phase with direct
 * access to the [PipelineContext].
 *
 * Why this exists: the new-plugin-API `onCall { }` hook hands the callback only an
 * [ApplicationCall], never the pipeline. A guard that responds inside `onCall` therefore sends a
 * response but cannot stop the pipeline — in Ktor 3.x the matched route handler still runs and
 * fires its side effects (session write, token mint, DB insert) even though the client sees the
 * rejection. Installing a guard through this hook lets it call [PipelineContext.finish]
 * immediately after responding, so the route handler never executes on a rejected request.
 */
internal object ShortCircuitPhase : Hook<ShortCircuitHandler> {
    override fun install(pipeline: ApplicationCallPipeline, handler: ShortCircuitHandler) {
        pipeline.intercept(ApplicationCallPipeline.ApplicationPhase.Plugins) {
            handler(this, context)
        }
    }
}
