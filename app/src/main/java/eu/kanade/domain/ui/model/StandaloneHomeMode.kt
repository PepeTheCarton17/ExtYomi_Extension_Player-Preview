package eu.kanade.domain.ui.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/** Initial screen of a generated standalone app. */
enum class StandaloneHomeMode(val titleRes: StringResource) {
    FEED(MR.strings.pref_standalone_home_mode_feed),
    NEW_HOME(MR.strings.pref_standalone_home_mode_new_home),
}
