package io.github.youndie.shildik.sample

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.publicvalue.multiplatform.oidc.ExperimentalOpenIdConnect
import org.publicvalue.multiplatform.oidc.appsupport.JvmCodeAuthFlowFactory

// The library listens on the port in the redirect address for the length of a sign-in. shildik
// matches a loopback address on any port when it was registered without one (RFC 8252 §7.3), so the
// registration says `http://127.0.0.1/redirect` and this port can change without touching it.
private const val REDIRECT = "http://127.0.0.1:8935/redirect"

@OptIn(ExperimentalOpenIdConnect::class)
fun main() {
    val factory = JvmCodeAuthFlowFactory()
    application {
        Window(onCloseRequest = ::exitApplication, title = "shildik sign-in") {
            SignInScreen(factory, REDIRECT, defaultIssuer = "http://localhost:8080/realms/sample")
        }
    }
}
