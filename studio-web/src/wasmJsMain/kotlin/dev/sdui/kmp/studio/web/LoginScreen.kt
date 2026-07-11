package dev.sdui.kmp.studio.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.sdui.kmp.studio.web.api.LoginResult
import dev.sdui.kmp.studio.web.api.StudioApi
import dev.sdui.kmp.studio.web.state.AuthState

/**
 * Studio login form.
 *
 * Centred card with email + password fields plus a "Sign in" button. On success, pushes the
 * returned token + role into [authState], which causes [App] to recompose into [MainShell].
 *
 * No SSO, no remember-me, no password reset link in the skeleton — those are S5 concerns.
 * Keep this composable focused on proving the login round-trip works end-to-end.
 */
@Composable
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

    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.widthIn(max = LOGIN_CARD_MAX_WIDTH).fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "sdui-kmp Studio")
                Text(text = "Sign in to manage screens, drafts, and rollouts.")

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !submitting,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                errorText?.let { Text(text = it) }

                Button(
                    onClick = { submitTick += 1 },
                    enabled = !submitting && email.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text("Signing in…")
                    } else {
                        Text("Sign in")
                    }
                }
            }
        }
    }
}

private val LOGIN_CARD_MAX_WIDTH = 420.dp
