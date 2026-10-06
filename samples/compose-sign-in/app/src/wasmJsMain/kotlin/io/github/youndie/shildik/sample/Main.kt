package io.github.youndie.shildik.sample

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.appsupport.PlatformCodeAuthFlow
import org.publicvalue.multiplatform.oidc.appsupport.WebCodeAuthFlowFactory

// The sign-in opens in a popup, and the redirect is handled by a second instance of this app loaded
// in that popup at `/redirect`, which posts the result back to the opener. Registered exactly: a
// browser's return address is an ordinary https/http address, not a loopback port.
@OptIn(ExperimentalComposeUiApi::class, ExperimentalOpenIdConnect::class)
fun main() {
    val redirect = "${window.location.origin}/redirect"
    ComposeViewport(document.body!!) {
        if (window.location.pathname.startsWith("/redirect")) {
            LaunchedEffect(Unit) { PlatformCodeAuthFlow.handleRedirect() }
        } else {
            SignInScreen(WebCodeAuthFlowFactory(), redirect, defaultIssuer = "http://localhost:8080/realms/sample")
        }
    }
}
