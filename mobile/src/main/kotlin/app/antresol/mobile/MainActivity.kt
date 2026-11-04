package app.antresol.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.antresol.ui.components.BrandSplash
import app.antresol.ui.theme.AntresolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AntresolTheme {
                BrandSplash()
            }
        }
    }
}
