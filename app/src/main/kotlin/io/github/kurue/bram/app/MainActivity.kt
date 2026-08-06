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
    }
}
