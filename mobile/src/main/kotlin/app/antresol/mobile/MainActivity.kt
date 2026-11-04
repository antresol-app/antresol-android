package app.antresol.mobile

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.antresol.mobile.ui.root.AntresolMobileRoot
import app.antresol.ui.theme.AntresolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark only: light system bar icons on transparent bars, content draws edge to edge.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val session = (application as AntresolMobileApp).session
        setContent {
            AntresolTheme {
                AntresolMobileRoot(session)
            }
        }
    }
}
