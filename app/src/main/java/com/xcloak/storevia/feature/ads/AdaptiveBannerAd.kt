package com.xcloak.storevia.feature.ads

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.xcloak.storevia.data.ads.AdsManager

@Composable
fun AdaptiveBannerAd(adsManager: AdsManager, modifier: Modifier = Modifier) {
    val canRequestAds by adsManager.canRequestAds.collectAsState()
    val activity = LocalContext.current as? Activity
    if (!canRequestAds || activity == null) return

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt().coerceAtLeast(1)
        val adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp)
        val adView = remember(activity, widthDp) {
            AdView(activity).apply {
                adUnitId = adsManager.bannerUnitId()
                setAdSize(adSize)
                loadAd(AdRequest.Builder().build())
            }
        }
        DisposableEffect(adView) {
            onDispose { adView.destroy() }
        }
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Advertisement",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
            AndroidView(
                factory = { adView },
                modifier = Modifier.fillMaxWidth().height(adSize.height.dp),
            )
        }
    }
}
