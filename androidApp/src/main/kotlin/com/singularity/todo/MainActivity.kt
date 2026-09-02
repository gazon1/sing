package com.singularity.todo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.singularity.todo.core.platform.PlatformContext
import org.koin.core.context.startKoin

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize platform context with Android context
        PlatformContext.initialize(this)

        startKoin {
            // Note: Full DI setup requires database and settings initialization
            // which is deferred to allow the app to start
        }

        setContent {
            App()
        }
    }
}
