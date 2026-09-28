package io.github.kurue.bram.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val container = (application as BramApplication).container
                    return MainViewModel(container) as T
                }
            },
        )[MainViewModel::class.java]
    }

    override fun onResume() {
        super.onResume()
        viewModel.setAppForeground(true)
        // Screen reading is turned on in system settings, so coming back is exactly when the
        // Capabilities state may have changed underneath us.
        viewModel.refreshAccessibility()
    }

    override fun onPause() {
        super.onPause()
        viewModel.setAppForeground(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Without this the window itself also shrinks when the keyboard opens, so a composable
        // that pads for the IME inset moves up twice: once with the window, once on its own.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            BramTheme {
                BramApp(viewModel)
            }
        }
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * Debug builds only: `am start -n <pkg>/.MainActivity --es bram.debug.benchmark "<profile>"`
     * (add `--ez bram.debug.sustained true` for the 5-minute run) starts a benchmark by profile
     * name, so measurements do not depend on driving the UI by screen coordinates. A release
     * build is not debuggable and ignores the extra.
     */
    private fun handleDebugIntent(intent: android.content.Intent?) {
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val name = intent?.getStringExtra("bram.debug.benchmark") ?: return
        if (!debuggable) return
        intent.removeExtra("bram.debug.benchmark")
        viewModel.debugBenchmarkByName(name, sustained = intent.getBooleanExtra("bram.debug.sustained", false))
    }
}
