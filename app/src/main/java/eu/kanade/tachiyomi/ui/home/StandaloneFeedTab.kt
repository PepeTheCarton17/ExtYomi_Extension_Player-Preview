package eu.kanade.tachiyomi.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Animetail-style Home feed. It lives on the house icon and is never mapped onto the library. */
data object StandaloneFeedTab : Tab {

    private val feedSettingsRequested = mutableStateOf(false)

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            return TabOptions(
                index = 0u,
                title = stringResource(MR.strings.label_home),
                icon = rememberVectorPainter(Icons.Outlined.Home),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // Matches Animetail: tapping the already-selected house opens the feed settings.
        feedSettingsRequested.value = true
    }

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { StandaloneFeedScreenModel() }
        StandaloneHomeFeedScreen(
            screenModel = screenModel,
            requestSettings = feedSettingsRequested.value,
            onSettingsHandled = { feedSettingsRequested.value = false },
        )
    }
}
