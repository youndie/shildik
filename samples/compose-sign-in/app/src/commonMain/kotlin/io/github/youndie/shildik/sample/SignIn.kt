package io.github.youndie.shildik.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.publicvalue.multiplatform.oidc.OpenIdConnectClient
import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory
import org.publicvalue.multiplatform.oidc.types.Jwt
import org.publicvalue.multiplatform.oidc.types.remote.AccessTokenResponse

/** The client registered for this sample (README): public, with one return address per platform. */
const val CLIENT_ID: String = "sample-app"

/**
 * Sign in, refresh, sign out — against a shildik realm, through the system browser.
 *
 * Everything that differs per platform comes in from the entry point: how the browser is opened and
 * the code caught ([factory]), and the address the code comes back to ([redirectUri]).
 */
@Composable
fun SignInScreen(
    factory: CodeAuthFlowFactory,
    redirectUri: String,
    defaultIssuer: String,
) {
    var issuer by remember { mutableStateOf(defaultIssuer) }
    var tokens by remember { mutableStateOf<AccessTokenResponse?>(null) }
    var status by remember { mutableStateOf("Signed out") }
    val coroutines = rememberCoroutineScope()

    fun client() =
        OpenIdConnectClient(discoveryUri = "${issuer.trimEnd('/')}/.well-known/openid-configuration") {
            clientId = CLIENT_ID
            this.redirectUri = redirectUri
            postLogoutRedirectUri = redirectUri
            scope = "openid profile email offline_access"
        }

    fun run(
        label: String,
        block: suspend () -> Unit,
    ) {
        status = "$label…"
        coroutines.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "$label failed: ${e.message}"
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.White).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText("shildik — sign in from an app")
        BasicText("Issuer")
        BasicTextField(
            value = issuer,
            onValueChange = { issuer = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().border(1.dp, Color.Gray).padding(8.dp),
        )
        BasicText("Returns to: $redirectUri")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button("Sign in") {
                run("Signing in") {
                    val client = client().apply { discover() }
                    tokens = factory.createAuthFlow(client).getAccessToken()
                    status = "Signed in"
                }
            }
            Button("Refresh") {
                val refresh = tokens?.refresh_token ?: return@Button run { status = "Nothing to refresh" }
                run("Refreshing") {
                    tokens = client().apply { discover() }.refreshToken(refresh)
                    status = "Refreshed"
                }
            }
            Button("Sign out") {
                val idToken = tokens?.id_token
                run("Signing out") {
                    val client = client().apply { discover() }
                    factory.createEndSessionFlow(client).endSession(idToken)
                    tokens = null
                    status = "Signed out"
                }
            }
        }

        BasicText(status)
        tokens?.id_token?.let { idToken ->
            val claims = Jwt.parse(idToken).payload
            BasicText("sub: ${claims.sub}")
            BasicText("iss: ${claims.iss}")
            BasicText("aud: ${claims.aud?.joinToString()}")
            BasicText("refresh token: ${if (tokens?.refresh_token != null) "yes" else "no"}")
        }
    }
}

@Composable
private fun Button(
    label: String,
    onClick: () -> Unit,
) {
    BasicText(
        label,
        modifier = Modifier.border(1.dp, Color.DarkGray).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
