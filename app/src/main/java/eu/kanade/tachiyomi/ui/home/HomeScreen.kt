package eu.kanade.tachiyomi.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabNavigator
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.NavBarLabelMode
import eu.kanade.domain.ui.model.NavTab
import eu.kanade.domain.ui.model.StandaloneHomeMode
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.download.DownloadsTab
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.ui.history.HistoriesTab
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryTab
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryTab
import eu.kanade.tachiyomi.ui.library.manga.NovelLibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import soup.compose.material.motion.animation.materialFadeThroughIn
import soup.compose.material.motion.animation.materialFadeThroughOut
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.LocalNavigationBarPadding
import tachiyomi.presentation.core.components.material.NavigationBar
import tachiyomi.presentation.core.components.material.NavigationRail
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/** Scale factor applied to the navigation bar icons (1f = default), set from Appearance settings. */
private val LocalNavBarIconScale = compositionLocalOf { 1f }

/** Default height of the (floating) navigation bar; the height slider scales this. */
private val NAV_BAR_BASE_HEIGHT = 80.dp

/** Max width of the floating nav pill, so it stays centered on wide tablets instead of stretching. */
private val FLOATING_NAV_BAR_MAX_WIDTH = 640.dp

object HomeScreen : Screen() {

    private val librarySearchEvent = Channel<String>()
    private val openTabEvent = Channel<Tab>()
    private val showBottomNavEvent = Channel<Boolean>()
    private val navigateUpEvent = Channel<Unit>(Channel.CONFLATED)

    private const val TAB_FADE_DURATION = 200
    private const val TAB_NAVIGATOR_KEY = "HomeTabs"

    private val uiPreferences: UiPreferences by injectLazy()

    @Composable
    override fun Content() {
        val enabledTabs by uiPreferences.bottomNavTabs().collectAsState()
        val tabOrder by uiPreferences.bottomNavOrder().collectAsState()
        val standaloneHomeMode by uiPreferences.standaloneHomeMode().collectAsState()
        val startScreen by uiPreferences.startScreen().collectAsState()
        val defaultTab = startScreen.tab
        // The Home tab follows Appearance > Navigation bar > Navigation style. The old separate
        // "New Home" switch used to override that choice and has been removed.
        val effectiveEnabledTabs = if (StandaloneScope.isActive) {
            NavTab.enabledInStandalone(enabledTabs, StandaloneScope.kind)
        } else {
            enabledTabs
        }
        val standaloneLibraryTab = when (StandaloneScope.kind) {
            StandaloneScope.KIND_MANGA -> MangaLibraryTab
            StandaloneScope.KIND_NOVEL -> NovelLibraryTab
            else -> AnimeLibraryTab
        }
        // FEED is the single source's catalogue; NEW_HOME is the Animetail-style feed on the
        // house icon. The library is never used as the feed.
        val standaloneFeedTab = if (standaloneHomeMode == StandaloneHomeMode.FEED) {
            StandaloneCatalogueTab
        } else {
            StandaloneFeedTab
        }
        val shownTabs = if (StandaloneScope.isActive) {
            // Honour "Estilo de navegación" inside the allowed tabs: Home, this app's library,
            // Updates and (optionally) History. Foreign kinds and Explore are never offered.
            buildList {
                NavTab.ordered(tabOrder).forEach { navTab ->
                    if (navTab.prefKey in effectiveEnabledTabs) {
                        add(
                            when (navTab) {
                                NavTab.Home -> standaloneFeedTab
                                NavTab.Anime, NavTab.Manga, NavTab.Novel -> standaloneLibraryTab
                                NavTab.Updates -> UpdatesTab
                                NavTab.History -> HistoriesTab
                                NavTab.Browse -> standaloneFeedTab
                            },
                        )
                    }
                }
                add(MoreTab)
            }
        } else {
            NavTab.shownTabs(effectiveEnabledTabs, tabOrder)
        }
        val allowedStandaloneTabs = StandaloneScope.kind.takeIf { StandaloneScope.isActive }
            ?.let(NavTab::allowedInStandalone)
            ?.map { it.prefKey }
            ?.toSet()
        val hiddenTabs = NavTab.hidden(effectiveEnabledTabs)
            .filter { navTab ->
                // A standalone app must not offer navigation into the other content kinds.
                allowedStandaloneTabs?.let { navTab.prefKey in it } ?: true
            }
            .map { it.tab }
        val homeTab = if (StandaloneScope.isActive) {
            standaloneFeedTab
        } else {
            // The start-screen tab may have been hidden; fall back to the first shown tab.
            defaultTab.takeIf { it in shownTabs } ?: shownTabs.first()
        }
        val floatingNavBar by uiPreferences.bottomNavFloating().collectAsState()
        val floatingNavBarAlpha by uiPreferences.bottomNavFloatingAlpha().collectAsState()
        val floatingNavBarBlur by uiPreferences.bottomNavFloatingBlur().collectAsState()
        val floatingNavBarHeight by uiPreferences.bottomNavFloatingHeight().collectAsState()
        val navBarLabelMode by uiPreferences.bottomNavLabelMode().collectAsState()
        val navBarIconScale by uiPreferences.bottomNavIconScale().collectAsState()
        // Backdrop for the floating nav bar's optional frosted-glass blur.
        val hazeState = remember { HazeState() }
        val navBarBlurActive = floatingNavBar && floatingNavBarBlur > 0
        val navigator = LocalNavigator.currentOrThrow
        // Keep a real history of tab changes so back returns to the panel the user actually left.
        // Root-level screens opened with navigator.push() keep using Voyager's own back stack.
        val tabHistory = remember { mutableStateListOf(homeTab) }
        TabNavigator(
            tab = homeTab,
            key = TAB_NAVIGATOR_KEY,
        ) { tabNavigator ->
            // Track every tab change: bottom bar, More, deep link or start-screen preference.
            LaunchedEffect(tabNavigator.current) {
                val current = tabNavigator.current
                if (tabHistory.lastOrNull() != current) {
                    tabHistory.add(current)
                }
            }
            // The start-screen preference is now live: switching it moves the tab navigator
            // immediately instead of only taking effect after a restart.
            LaunchedEffect(homeTab) {
                if (tabNavigator.current != homeTab) {
                    if (tabHistory.lastOrNull() != homeTab) {
                        tabHistory.add(homeTab)
                    }
                    tabNavigator.current = homeTab
                }
            }
            val navigateUpTab: () -> Unit = {
                if (tabHistory.size > 1) {
                    tabHistory.removeAt(tabHistory.lastIndex)
                    val target = tabHistory.last()
                    if (tabNavigator.current != target) {
                        tabNavigator.current = target
                    }
                }
            }
            LaunchedEffect(Unit) {
                navigateUpEvent.receiveAsFlow().collectLatest {
                    navigateUpTab()
                }
            }
            // Restore the root navigator for tab content. LocalNavigateUp is provided globally by
            // MainActivity so pushed screens and tab screens share the same contextual back action.
            CompositionLocalProvider(LocalNavigator provides navigator) {
                Scaffold(
                    startBar = {
                        // Side rail only in classic (non-floating) mode on wide screens. With the
                        // floating nav bar enabled we use the floating bottom bar everywhere (phones
                        // AND tablets), so it behaves the same regardless of screen size/orientation.
                        if (isTabletUi() && !floatingNavBar) {
                            NavigationRail {
                                shownTabs.fastForEach {
                                    NavigationRailItem(it)
                                }
                            }
                        }
                    },
                    bottomBar = {
                        if (!isTabletUi() || floatingNavBar) {
                            val bottomNavVisible by produceState(initialValue = true) {
                                showBottomNavEvent.receiveAsFlow().collectLatest { value = it }
                            }
                            AnimatedVisibility(
                                visible = bottomNavVisible && tabNavigator.current !in hiddenTabs,
                                enter = expandVertically(),
                                exit = shrinkVertically(),
                            ) {
                                CompositionLocalProvider(
                                    LocalNavBarIconScale provides navBarIconScale / 100f,
                                ) {
                                    if (floatingNavBar) {
                                        // Translucent floating pill: a fill + subtle outline rather
                                        // than an elevation shadow, which would show through the
                                        // translucent surface as a grey band and clump at the corners.
                                        val pillShape = RoundedCornerShape(28.dp)
                                        val pillAlpha = floatingNavBarAlpha / 100f
                                        val navBarBlurRadius = floatingNavBarBlur.dp
                                        val navBarHeight = NAV_BAR_BASE_HEIGHT * (floatingNavBarHeight / 100f)
                                        // Colors captured here; the Haze effect block is not composable.
                                        // Blur off: a solid translucent fill at the chosen opacity.
                                        // Blur on: a lighter tint so the frosted backdrop stays visible.
                                        val solidTint = MaterialTheme.colorScheme.surfaceContainerHigh
                                            .copy(alpha = pillAlpha)
                                        val glassTint = MaterialTheme.colorScheme.surfaceContainerHigh
                                            .copy(alpha = pillAlpha * 0.5f)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            // Cap the pill width so it stays a centered floating pill
                                            // on wide tablets instead of stretching edge to edge.
                                            Box(
                                                modifier = Modifier.widthIn(max = FLOATING_NAV_BAR_MAX_WIDTH),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .matchParentSize()
                                                        .clip(pillShape)
                                                        .then(
                                                            // Blur on: frost the content scrolling behind
                                                            // the pill (the fill becomes the glass tint).
                                                            // Blur off: a plain translucent fill.
                                                            if (navBarBlurActive) {
                                                                Modifier.hazeEffect(state = hazeState) {
                                                                    blurRadius = navBarBlurRadius
                                                                    backgroundColor = Color.Transparent
                                                                    tints = listOf(HazeTint(glassTint))
                                                                    fallbackTint = HazeTint(glassTint)
                                                                    noiseFactor = 0f
                                                                }
                                                            } else {
                                                                Modifier.background(color = solidTint)
                                                            },
                                                        )
                                                        .border(
                                                            width = 1.dp,
                                                            color = MaterialTheme.colorScheme.outlineVariant
                                                                .copy(alpha = pillAlpha),
                                                            shape = pillShape,
                                                        ),
                                                )
                                                NavigationBar(
                                                    containerColor = Color.Transparent,
                                                    tonalElevation = 0.dp,
                                                    windowInsets = WindowInsets(0),
                                                    barHeight = navBarHeight,
                                                ) {
                                                    shownTabs.fastForEach {
                                                        NavigationBarItem(
                                                            it,
                                                            showLabel = navBarLabelMode == NavBarLabelMode.BESIDE,
                                                            labelsBelow = navBarLabelMode == NavBarLabelMode.BELOW,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Classic bar: full width, flush to the bottom, no rounded corners.
                                        NavigationBar {
                                            shownTabs.fastForEach {
                                                NavigationBarItem(
                                                    it,
                                                    showLabel = navBarLabelMode == NavBarLabelMode.BESIDE,
                                                    labelsBelow = navBarLabelMode == NavBarLabelMode.BELOW,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    contentWindowInsets = WindowInsets(0),
                ) { contentPadding ->
                    val layoutDirection = LocalLayoutDirection.current
                    // Floating nav bar (bottom pill) is used on both phones and tablets now.
                    val floating = floatingNavBar
                    // Floating nav bar: drop the bottom reservation from the content box so tab
                    // content fills behind the translucent bar, and hand that reservation to the
                    // tab scaffolds (via LocalNavigationBarPadding) so their lists still clear it.
                    val boxPadding = if (floating) {
                        PaddingValues(
                            top = contentPadding.calculateTopPadding(),
                            start = contentPadding.calculateStartPadding(layoutDirection),
                            end = contentPadding.calculateEndPadding(layoutDirection),
                            bottom = 0.dp,
                        )
                    } else {
                        contentPadding
                    }
                    val navBarPadding = if (floating) {
                        PaddingValues(bottom = contentPadding.calculateBottomPadding())
                    } else {
                        PaddingValues()
                    }
                    CompositionLocalProvider(LocalNavigationBarPadding provides navBarPadding) {
                        Box(
                            modifier = Modifier
                                .padding(boxPadding)
                                .consumeWindowInsets(boxPadding)
                                // Source for the floating nav bar's frosted-glass blur; only
                                // recorded into a layer while the blur is actually enabled.
                                .then(if (navBarBlurActive) Modifier.hazeSource(hazeState) else Modifier),
                        ) {
                            AnimatedContent(
                                targetState = tabNavigator.current,
                                transitionSpec = {
                                    materialFadeThroughIn(
                                        initialScale = 1f,
                                        durationMillis = TAB_FADE_DURATION,
                                    ) togetherWith
                                        materialFadeThroughOut(durationMillis = TAB_FADE_DURATION)
                                },
                                label = "tabContent",
                            ) {
                                tabNavigator.saveableState(key = "currentTab", it) {
                                    it.Content()
                                }
                            }
                        }
                    }
                }
            }

            val goToStartScreen = { tabNavigator.current = homeTab }
            // Voyager already pops the root navigator when it can; only handle the tab level here.
            BackHandler(
                enabled = !navigator.canPop && tabHistory.size > 1,
                onBack = navigateUpTab,
            )

            LaunchedEffect(Unit) {
                launch {
                    librarySearchEvent.receiveAsFlow().collectLatest {
                        goToStartScreen()
                        when (defaultTab) {
                            AnimeLibraryTab -> AnimeLibraryTab.search(it)
                            MangaLibraryTab -> MangaLibraryTab.search(it)
                            else -> {}
                        }
                    }
                }
                launch {
                    openTabEvent.receiveAsFlow().collectLatest {
                        val target = when (it) {
                            is Tab.Home -> standaloneFeedTab
                            is Tab.AnimeLib -> {
                                if (StandaloneScope.isActive) standaloneLibraryTab else AnimeLibraryTab
                            }
                            is Tab.Library -> {
                                if (StandaloneScope.isActive) standaloneLibraryTab else MangaLibraryTab
                            }
                            is Tab.Updates -> UpdatesTab
                            is Tab.History -> HistoriesTab
                            is Tab.Browse -> {
                                if (StandaloneScope.isActive) {
                                    standaloneFeedTab
                                } else {
                                    if (it.toExtensions) {
                                        if (!it.anime) {
                                            BrowseTab.showExtension()
                                        } else {
                                            BrowseTab.showAnimeExtension()
                                        }
                                    }
                                    BrowseTab
                                }
                            }
                            is Tab.More -> MoreTab
                        }
                        if (tabNavigator.current != target) {
                            tabNavigator.current = target
                        }

                        if (it is Tab.AnimeLib &&
                            it.animeIdToOpen != null &&
                            (!StandaloneScope.isActive || StandaloneScope.appliesTo(StandaloneScope.KIND_ANIME))
                        ) {
                            navigator.push(AnimeScreen(it.animeIdToOpen))
                        }
                        if (it is Tab.Library &&
                            it.mangaIdToOpen != null &&
                            (
                                !StandaloneScope.isActive ||
                                    StandaloneScope.appliesTo(StandaloneScope.KIND_MANGA) ||
                                    StandaloneScope.appliesTo(StandaloneScope.KIND_NOVEL)
                                )
                        ) {
                            navigator.push(MangaScreen(it.mangaIdToOpen))
                        }
                        if (it is Tab.More && it.toDownloads) {
                            navigator.push(DownloadsTab)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RowScope.NavigationBarItem(
        tab: eu.kanade.presentation.util.Tab,
        showLabel: Boolean,
        labelsBelow: Boolean = false,
    ) {
        val tabNavigator = LocalTabNavigator.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val selected = tabNavigator.current::class == tab::class
        val onClick: () -> Unit = {
            if (!selected) {
                tabNavigator.current = tab
            } else {
                scope.launch { tab.onReselect(navigator) }
            }
        }

        if (labelsBelow) {
            // Classic layout: icon (with the selected pill behind it) on top, label always below.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(onClick = onClick),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(percent = 50),
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        NavigationIconItem(tab)
                    }
                }
                Text(
                    text = tab.options.title,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
                )
            }
            return
        }

        // Pill layout: only the selected tab expands to show its label beside the icon.
        val weight by animateFloatAsState(
            targetValue = if (selected && showLabel) 2.0f else 1f,
            label = "navItemWeight",
        )
        Box(
            modifier = Modifier
                .weight(weight)
                .fillMaxHeight()
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(percent = 50),
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavigationIconItem(tab)
                    AnimatedVisibility(visible = selected && showLabel) {
                        Text(
                            text = tab.options.title,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun NavigationRailItem(tab: eu.kanade.presentation.util.Tab) {
        val tabNavigator = LocalTabNavigator.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val selected = tabNavigator.current::class == tab::class
        NavigationRailItem(
            selected = selected,
            onClick = {
                if (!selected) {
                    tabNavigator.current = tab
                } else {
                    scope.launch { tab.onReselect(navigator) }
                }
            },
            icon = { NavigationIconItem(tab) },
            label = {
                Text(
                    text = tab.options.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            alwaysShowLabel = true,
        )
    }

    @Composable
    private fun NavigationIconItem(tab: eu.kanade.presentation.util.Tab) {
        BadgedBox(
            badge = {
                when {
                    UpdatesTab::class.isInstance(tab) -> {
                        val count by produceState(initialValue = 0) {
                            val pref = Injekt.get<LibraryPreferences>()
                            combine(
                                pref.newAnimeUpdatesCount().changes(),
                                pref.newMangaUpdatesCount().changes(),
                                pref.newNovelUpdatesCount().changes(),
                            ) { countAnime, countManga, countNovel ->
                                countAnime + countManga + countNovel
                            }
                                .collectLatest { value = if (pref.newShowUpdatesCount().get()) it else 0 }
                        }
                        if (count > 0) {
                            Badge {
                                val desc = pluralStringResource(
                                    MR.plurals.notification_chapters_generic,
                                    count = count,
                                    count,
                                )
                                Text(
                                    text = count.toString(),
                                    modifier = Modifier.semantics { contentDescription = desc },
                                )
                            }
                        }
                    }
                    BrowseTab::class.isInstance(tab) -> {
                        val count by produceState(initialValue = 0) {
                            val pref = Injekt.get<SourcePreferences>()
                            combine(
                                pref.mangaExtensionUpdatesCount().changes(),
                                pref.animeExtensionUpdatesCount().changes(),
                            ) { extCount, animeExtCount -> extCount + animeExtCount }
                                .collectLatest { value = it }
                        }
                        if (count > 0) {
                            Badge {
                                val desc = pluralStringResource(
                                    MR.plurals.update_check_notification_ext_updates,
                                    count = count,
                                    count,
                                )
                                Text(
                                    text = count.toString(),
                                    modifier = Modifier.semantics { contentDescription = desc },
                                )
                            }
                        }
                    }
                }
            },
        ) {
            Icon(
                painter = tab.options.icon!!,
                contentDescription = tab.options.title,
                // TODO: https://issuetracker.google.com/u/0/issues/316327367
                tint = LocalContentColor.current,
                modifier = Modifier.size(28.dp * LocalNavBarIconScale.current),
            )
        }
    }

    suspend fun search(query: String) {
        librarySearchEvent.send(query)
    }

    suspend fun openTab(tab: Tab) {
        openTabEvent.send(tab)
    }

    suspend fun showBottomNav(show: Boolean) {
        showBottomNavEvent.send(show)
    }

    /** Requests a contextual back from the root navigator; falls back to the tab history. */
    fun navigateUp() {
        navigateUpEvent.trySend(Unit)
    }

    sealed interface Tab {
        data object Home : Tab
        data class AnimeLib(val animeIdToOpen: Long? = null) : Tab
        data class Library(val mangaIdToOpen: Long? = null) : Tab
        data object Updates : Tab
        data object History : Tab
        data class Browse(val toExtensions: Boolean = false, val anime: Boolean = false) : Tab
        data class More(val toDownloads: Boolean) : Tab
    }
}
