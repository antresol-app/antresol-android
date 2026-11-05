package app.antresol.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antresol.ui.R
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolGradients
import app.antresol.ui.theme.AntresolTheme

/** The launch splash: brand gradient (160°) with the logo mark and the wordmark. */
@Composable
fun BrandSplash(modifier: Modifier = Modifier, markSize: Dp = 120.dp) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AntresolGradients.brand),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LogoMark(size = markSize)
        Text(
            text = stringResource(R.string.brand_name),
            style = Antresol.typography.heroTitle.copy(fontSize = 52.sp, fontWeight = FontWeight.Bold),
        )
    }
}

/** The glass house mark with the play triangle. */
@Composable
fun LogoMark(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Image(
        painter = painterResource(R.drawable.logo_mark),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}

/** The logo mark on the purple brand tile (rounded square, 160° gradient), as in the app icon and top bars. */
@Composable
fun LogoTile(modifier: Modifier = Modifier, size: Dp = 30.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * TILE_RADIUS_FRACTION))
            .background(AntresolGradients.brand),
        contentAlignment = Alignment.Center,
    ) {
        LogoMark(size = size * MARK_FRACTION)
    }
}

/** 7 dp corners on a 30 dp tile. */
private const val TILE_RADIUS_FRACTION = 7f / 30f
private const val MARK_FRACTION = 0.74f

@Preview(widthDp = 960, heightDp = 540)
@Composable
private fun BrandSplashPreview() = AntresolTheme { BrandSplash() }
