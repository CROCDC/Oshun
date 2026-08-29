package com.oshun.gpsbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.oshun.gpsbridge.ui.OshunShell

/**
 * The single activity that hosts the app shell.
 *
 * The screens themselves live in `ui/`: the bridge, the test data, the installed version.
 * The activity holds none of it — it only puts the shell on screen.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                OshunShell()
            }
        }
    }
}
