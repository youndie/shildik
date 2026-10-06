package io.github.youndie.shildik.sample.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.youndie.shildik.sample.SignInScreen
import org.publicvalue.multiplatform.oidc.appsupport.AndroidCodeAuthFlowFactory

class MainActivity : ComponentActivity() {
    // One factory, registered before the activity starts: the library attaches to its lifecycle to
    // receive the result from Custom Tabs.
    private val factory = AndroidCodeAuthFlowFactory(useWebView = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        factory.registerActivity(this)
        setContent {
            // 10.0.2.2 is the host machine as the emulator sees it.
            SignInScreen(factory, "io.github.youndie.shildik.sample:/callback", defaultIssuer = "http://10.0.2.2:8080/realms/sample")
        }
    }
}
