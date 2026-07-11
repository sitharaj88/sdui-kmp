package dev.sdui.kmp.widgetsforms

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.sdui.kmp.protocol.Keyboard
import dev.sdui.kmp.protocol.SchemaVersion
import dev.sdui.kmp.protocol.TextField as TextFieldNode
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.runtime.LocalStateStore
import dev.sdui.kmp.runtime.NodeRenderer
import dev.sdui.kmp.runtime.applyA11y
import dev.sdui.kmp.runtime.resolve
import kotlin.reflect.KClass
import kotlinx.serialization.json.JsonPrimitive

public object TextFieldRenderer : NodeRenderer<TextFieldNode> {
    override val nodeClass: KClass<TextFieldNode> = TextFieldNode::class
    override val handledVersions: ClosedRange<SchemaVersion> = SchemaVersion.V1..SchemaVersion(Int.MAX_VALUE)

    @Composable
    override fun Render(node: TextFieldNode, modifier: Modifier) {
        val store = LocalStateStore.current
        val snapshot by store.snapshot
        val current = (snapshot[node.path] as? JsonPrimitive)?.content ?: ""
        var touched by remember(node.id) { mutableStateOf(false) }

        val error = if (touched) node.validation?.check(current, store) else null

        // Compute an accessibility-name fallback. When the server didn't supply
        // `a11y.label` we project the resolved placeholder text as the contentDescription
        // — a placeholder alone is not a WCAG-conformant label (3.3.2 Labels or
        // Instructions), but it's strictly better than an unnamed input. Servers should
        // prefer setting `a11y.label`; this is the safety net.
        val a11yFallbackName: String? = if (node.a11y?.label == null) {
            node.placeholder?.resolve(store)
        } else {
            null
        }

        OutlinedTextField(
            value = current,
            onValueChange = { new ->
                touched = true
                store.update(node.path, JsonPrimitive(new))
            },
            modifier = modifier
                .semantics(mergeDescendants = true) {
                    if (a11yFallbackName != null) contentDescription = a11yFallbackName
                    if (error != null) {
                        // SC 3.3.1 Error Identification — surface the validation message
                        // through the screen-reader's "error" semantic *and* update
                        // stateDescription so the live region announces the change.
                        error(error)
                        stateDescription = error
                    }
                }
                .applyA11y(node.a11y, store),
            placeholder = node.placeholder?.let { resolvedPlaceholder(it) },
            isError = error != null,
            supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            keyboardOptions = node.keyboard.toKeyboardOptions(),
            visualTransformation = if (node.secure) PasswordVisualTransformation() else VisualTransformation.None,
            singleLine = true,
        )
    }
}

@Composable
private fun resolvedPlaceholder(value: Value<String>): @Composable () -> Unit {
    val store = LocalStateStore.current
    @Suppress("UNUSED_VARIABLE") val subscribe = store.snapshot.value
    val text = value.resolve(store)
    return { Text(text) }
}

private fun Keyboard.toKeyboardOptions(): KeyboardOptions = when (this) {
    Keyboard.Text -> KeyboardOptions(keyboardType = KeyboardType.Text)
    Keyboard.Email -> KeyboardOptions(keyboardType = KeyboardType.Email)
    Keyboard.Number -> KeyboardOptions(keyboardType = KeyboardType.Number)
    Keyboard.Phone -> KeyboardOptions(keyboardType = KeyboardType.Phone)
    Keyboard.Url -> KeyboardOptions(keyboardType = KeyboardType.Uri)
    Keyboard.Password -> KeyboardOptions(keyboardType = KeyboardType.Password)
}
