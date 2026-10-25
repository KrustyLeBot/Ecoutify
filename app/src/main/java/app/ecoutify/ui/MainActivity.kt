package app.ecoutify.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null) handleShare(intent)
        setContent { EcoutifyTheme { EcoutifyScreen(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Share" from Shazam (e.g. "TITLE - Artist1, Artist2 & Artist3") searches for that text. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let(vm::searchShared)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshStorage()
        vm.syncOnOpen()
    }

    override fun onDestroy() {
        // Leaving the app with Back (not a rotation) stops the music; Home keeps it playing.
        if (isFinishing) vm.player.stopAll()
        super.onDestroy()
    }
}
