package io.github.youndie.shildik.sample

import androidx.compose.ui.window.ComposeUIViewController
import org.publicvalue.multiplatform.oidc.appsupport.IosCodeAuthFlowFactory
import platform.UIKit.UIViewController

// `ASWebAuthenticationSession` returns to the app through the scheme of the redirect address and
// needs no Info.plist entry for it. Host it from an Xcode app: `MainViewController()`.
private const val REDIRECT = "io.github.youndie.shildik.sample:/callback"

@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    val factory = IosCodeAuthFlowFactory()
    return ComposeUIViewController {
        SignInScreen(factory, REDIRECT, defaultIssuer = "http://localhost:8080/realms/sample")
    }
}
