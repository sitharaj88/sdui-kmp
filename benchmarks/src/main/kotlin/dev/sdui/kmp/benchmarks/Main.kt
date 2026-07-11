package dev.sdui.kmp.benchmarks

import dev.sdui.kmp.protocol.Action
import dev.sdui.kmp.protocol.Column
import dev.sdui.kmp.protocol.Destination
import dev.sdui.kmp.protocol.NodeId
import dev.sdui.kmp.protocol.PatchOp
import dev.sdui.kmp.protocol.Predicate
import dev.sdui.kmp.protocol.Screen
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.ScreenId
import dev.sdui.kmp.protocol.SduiJson
import dev.sdui.kmp.protocol.StatePath
import dev.sdui.kmp.protocol.Text
import dev.sdui.kmp.protocol.TreePatch
import dev.sdui.kmp.protocol.UiNode
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.runtime.StateStore
import dev.sdui.kmp.runtime.apply
import dev.sdui.kmp.runtime.evaluate
import dev.sdui.kmp.runtime.resolve
import kotlinx.serialization.json.JsonPrimitive

/**
 * Entry point: `./gradlew :benchmarks:run`.
 *
 * Runs each benchmark once and prints a formatted summary. For regression hunting, capture
 * stdout before and after a change and diff the numbers. For absolute budget enforcement, a
 * later phase can wrap this in a proper JMH harness with confidence intervals.
 */
public fun main() {
    println("sdui-kmp benchmarks")
    println("=".repeat(90))

    runAll().forEach(::println)

    println("=".repeat(90))
}

internal fun runAll(): List<BenchResult> {
    val screen100 = makeScreen(nodeCount = 100)
    val screen100Json = SduiJson.encodeToString(Screen.serializer(), screen100)
    val tenOps = (1..10).map { i ->
        PatchOp.Replace(
            nodeId = NodeId("row/$i"),
            node = Text(id = NodeId("row/$i"), content = Value.ofString("updated $i")),
        )
    }
    val patch = TreePatch(ops = tenOps)
    val store = StateStore(
        mapOf(
            StatePath("a") to JsonPrimitive(true),
            StatePath("b") to JsonPrimitive(42),
            StatePath("name") to JsonPrimitive("alice"),
        ),
    )
    val predicate: Predicate = Predicate.All(
        predicates = listOf(
            Predicate.Eq(StatePath("a"), JsonPrimitive(true)),
            Predicate.Not(Predicate.IsEmpty(StatePath("name"))),
        ),
    )
    val template: Value<String> = Value.template(
        pattern = "Hello {name}, count={b}",
        bindings = mapOf("name" to StatePath("name"), "b" to StatePath("b")),
    )

    return listOf(
        bench("json encode: 100-node Screen", iterations = 500) {
            SduiJson.encodeToString(Screen.serializer(), screen100)
        },
        bench("json decode: 100-node Screen", iterations = 500) {
            SduiJson.decodeFromString(Screen.serializer(), screen100Json)
        },
        bench("tree patch: 10 ops on 100-node Screen", iterations = 5_000) {
            screen100.apply(patch)
        },
        bench("predicate: All(Eq + Not(IsEmpty))", iterations = 500_000) {
            predicate.evaluate(store)
        },
        bench("value resolve: template with 2 bindings", iterations = 100_000) {
            template.resolve(store)
        },
        bench("state store: update + read", iterations = 500_000) {
            store.update(StatePath("x"), JsonPrimitive("v"))
            store.read(StatePath("x"))
        },
    )
}

private fun makeScreen(nodeCount: Int): Screen {
    val rows: List<UiNode> = (1..nodeCount).map { i ->
        Text(id = NodeId("row/$i"), content = Value.ofString("row $i"))
    }
    return Screen(
        id = ScreenId("bench"),
        version = SchemaVersion.V1,
        root = Column(id = NodeId("root"), children = rows + terminalButton()),
    )
}

private fun terminalButton(): UiNode = dev.sdui.kmp.protocol.Button(
    id = NodeId("cta"),
    label = Value.ofString("Go"),
    action = Action.Navigate(Destination.Back()),
)
