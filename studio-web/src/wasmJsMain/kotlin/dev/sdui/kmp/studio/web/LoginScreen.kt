package dev.sdui.kmp.studio.web

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.studio.web.api.LoginResult
import dev.sdui.kmp.studio.web.api.StudioApi
import dev.sdui.kmp.studio.web.components.studioFieldColors
import dev.sdui.kmp.studio.web.state.AuthState
import dev.sdui.kmp.studio.web.theme.StudioIcons

/**
 * Studio login form.
 *
 * Centred, hairline-bordered surface with brand mark, email + password fields, and a
 * "Sign in" button, floating on a faint radial accent glow. On success, pushes the returned
 * token + role into [authState], which causes [App] to recompose into [MainShell].
 *
 * No SSO, no remember-me, no password reset link — those are future concerns. Keep this
 * composable focused on proving the login round-trip works end-to-end.
 */
@Composable
@Suppress("LongMethod")
public fun LoginScreen(api: StudioApi, authState: AuthState) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // A monotonic submit token: any non-zero value triggers the LaunchedEffect below to fire
    // exactly once. Using a counter (rather than a Boolean flag toggled in the click handler)
    // sidesteps the "user clicks twice quickly while submitting" race without needing a Mutex.
    var submitTick by remember { mutableStateOf(0) }

    LaunchedEffect(submitTick) {
        if (submitTick == 0) return@LaunchedEffect
        submitting = true
        errorText = null
        try {
            when (val result = api.login(email = email.trim(), password = password)) {
                is LoginResult.Success -> authState.signIn(
                    token = result.token,
                    role = result.role,
                    email = result.email,
                )
                LoginResult.InvalidCredentials -> errorText = "Invalid email or password."
                is LoginResult.Failure -> errorText = "Login failed (HTTP ${result.statusCode})."
            }
        } catch (t: Throwable) {
            // Network error, CORS preflight failure, server down — surface a generic message
            // rather than the exception class, which means nothing to a Studio operator.
            errorText = "Could not reach the Studio server: ${t.message ?: t::class.simpleName}"
        } finally {
            submitting = false
        }
    }

    val glow = MaterialTheme.colorScheme.primary.copy(alpha = GLOW_ALPHA)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .background(Brush.radialGradient(colors = listOf(glow, androidx.compose.ui.graphics.Color.Transparent)))
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = LOGIN_CARD_MAX_WIDTH).fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(LOGIN_CARD_PADDING).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    StudioLogoMark(size = LOGIN_LOGO_SIZE)
                    Text(text = "sdui-kmp Studio", style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    text = "Sign in to manage screens, drafts, and rollouts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !submitting,
                    colors = studioFieldColors(),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !submitting,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = studioFieldColors(),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )

                errorText?.let { message ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(10.dp),
                        ) {
                            Icon(
                                imageVector = StudioIcons.ErrorCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(ERROR_ICON_SIZE),
                            )
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                Button(
                    onClick = { submitTick += 1 },
                    enabled = !submitting && email.isNotBlank() && password.isNotEmpty(),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().height(LOGIN_BUTTON_HEIGHT),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).size(BUTTON_SPINNER_SIZE),
                            strokeWidth = BUTTON_SPINNER_STROKE,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text("Signing in…")
                    } else {
                        Text("Sign in")
                    }
                }
            }
        }
    }
}

private val LOGIN_CARD_MAX_WIDTH = 400.dp
private val LOGIN_CARD_PADDING = 28.dp
private val LOGIN_LOGO_SIZE = 28.dp
private val LOGIN_BUTTON_HEIGHT = 40.dp
private val BUTTON_SPINNER_SIZE = 16.dp
private val BUTTON_SPINNER_STROKE = 2.dp
private val ERROR_ICON_SIZE = 14.dp
private const val GLOW_ALPHA = 0.06f
