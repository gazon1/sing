package com.singularity.todo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.log.initLogging
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Guard against rotation/config-change restarts — Koin is process-scoped,
        // startKoin() on every onCreate throws KoinApplicationAlreadyStartedException.
        if (GlobalContext.getOrNull() == null) {
            initLogging(BuildConfig.DEBUG, version = "0.1.0")
            startKoin {
                androidContext(this@MainActivity)
                modules(
                    platformModule(),
                    coreLoggingModule(),
                    domainModule()
                )
            }
        }

        setContent {
            App()
        }
    }
}
