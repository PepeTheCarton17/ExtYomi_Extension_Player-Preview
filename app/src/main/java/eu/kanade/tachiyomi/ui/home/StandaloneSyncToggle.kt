package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import eu.kanade.domain.ui.UiPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Shown at the top of Home > Filtrar in both the host and generated standalone apps. */
@Composable
fun StandaloneSyncToggle() {
    val preference = remember { Injekt.get<UiPreferences>().standaloneAutoSync() }
    CheckboxItem(
        label = stringResource(MR.strings.pref_standalone_auto_sync),
        pref = preference,
    )
}
