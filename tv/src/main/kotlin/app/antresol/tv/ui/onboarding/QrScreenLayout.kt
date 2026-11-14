package app.antresol.tv.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.ui.R
import app.antresol.ui.components.LogoTile
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors

/** Side of the QR tile on the TV's QR screens (about a quarter of the screen width, like the Apple TV app). */
val TvQrSize = 260.dp

/**
 * The TV's two QR screens (first-run setup, sign-in with the phone), laid out like the Apple TV
 * app: brand, title, text and the remote fallbacks on the left; the QR (and what goes under it)
 * on the right. Without [qr] the left column stands alone in the middle.
 */
@Composable
fun QrScreenLayout(
    modifier: Modifier = Modifier,
    qr: (@Composable ColumnScope.() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    0f to AntresolColors.BrandStart.copy(alpha = 0.45f),
                    1f to AntresolColors.Background,
                    radius = 1400f,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(460.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BrandRow()
                content()
            }
            if (qr != null) {
                Column(Modifier.width(TvQrSize + 40.dp), horizontalAlignment = Alignment.CenterHorizontally, content = qr)
            }
        }
    }
}

@Composable
private fun BrandRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LogoTile(size = 34.dp)
        Text(stringResource(R.string.brand_name), style = Antresol.typography.sectionTitle)
    }
}
