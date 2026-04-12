package com.andreacanes.panemgmt

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.andreacanes.panemgmt.service.NotificationChannels
import com.andreacanes.panemgmt.ui.theme.PaneMgmtTheme
import java.net.URLDecoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationChannels.register(this)
        setContent {
            PaneMgmtTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PaneMgmtApp()
                }
            }
        }
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (data.scheme != "panemgmt" || data.host != "pane") return
        val encoded = data.pathSegments.firstOrNull() ?: return
        val paneId = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return
        DeepLinkBus.post(paneId)
    }
}
