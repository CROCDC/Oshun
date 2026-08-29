package com.oshun.gpsbridge.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oshun.gpsbridge.LogActivity
import com.oshun.gpsbridge.R
import kotlinx.coroutines.launch

/**
 * The screens the side menu switches between.
 *
 * Everything used to live in one scrolling column, and it had grown long enough that the
 * Start button — the only control that matters while casting off — competed for space with
 * things you look at once a month. What stays on the bridge screen is what you touch on the
 * water; the rest moved behind the menu.
 */
enum class Destination(
    @StringRes val titleRes: Int,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val tag: String,
) {
    /** Start/stop, network, transports, status: the working screen. */
    BRIDGE(R.string.app_name, R.string.nav_bridge, Icons.Filled.Home, "nav_bridge"),

    /** The internet AIS feed: its key, and the warning about what it does not show. */
    AIS(R.string.nav_ais, R.string.nav_ais, Icons.Filled.Place, "nav_ais"),

    /** The simulated boat and its AIS targets, off the working screen and opt-in. */
    TEST_DATA(R.string.nav_test_data, R.string.nav_test_data, Icons.Filled.PlayArrow, "nav_test_data"),

    /** Which build is installed and where a newer one comes from. */
    VERSION(R.string.nav_version, R.string.nav_version, Icons.Filled.Info, "nav_version"),
}

/**
 * The app shell: a side menu, a top bar, and whichever screen the menu last selected.
 *
 * The bridge configuration is held here rather than inside a screen because two screens now
 * edit it — test mode moved out, and turning it on changes what the bridge screen requires
 * of the network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OshunShell() {
    val context = LocalContext.current
    val settings = rememberBridgeSettings()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var destination by rememberSaveable { mutableStateOf(Destination.BRIDGE) }

    // Back closes the menu, then returns to the bridge screen, and only then leaves the app:
    // the working screen is where a phone in a pocket should end up.
    BackHandler(enabled = drawerState.isOpen || destination != Destination.BRIDGE) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            destination = Destination.BRIDGE
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                // The header deliberately does not repeat the app name the top bar already
                // shows: two identical titles on screen at once is noise, not branding.
                Text(
                    stringResource(R.string.nav_menu_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                )

                Destination.entries.forEach { entry ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(entry.labelRes)) },
                        icon = { Icon(entry.icon, contentDescription = null) },
                        selected = entry == destination,
                        onClick = {
                            destination = entry
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .testTag(entry.tag),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // The log is its own activity — it outlives this shell's state and is shared
                // straight from there — so the menu launches it instead of swapping content.
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.log_title)) },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        context.startActivity(Intent(context, LogActivity::class.java))
                    },
                    modifier = Modifier
                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                        .testTag("nav_log"),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(destination.titleRes)) },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("open_drawer"),
                        ) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = stringResource(R.string.nav_open_menu),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            val content = Modifier.padding(padding)
            when (destination) {
                Destination.BRIDGE -> BridgeScreen(
                    settings = settings,
                    onOpenTestData = { destination = Destination.TEST_DATA },
                    modifier = content,
                )
                Destination.AIS -> AisFeedScreen(settings = settings, modifier = content)
                Destination.TEST_DATA -> TestDataScreen(settings = settings, modifier = content)
                Destination.VERSION -> VersionScreen(modifier = content)
            }
        }
    }
}
