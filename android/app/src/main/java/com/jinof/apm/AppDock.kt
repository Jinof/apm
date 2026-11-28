package com.jinof.apm

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val EXTRA_DOCK_DESTINATION = "apm_dock_destination"
private const val PAGER_SNAP_POSITIONAL_THRESHOLD = 0.32f
private val PAGER_SETTLE_ANIMATION_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 650f,
)
private class PagerNavigationState {
    var job: Job? = null
    var transition by mutableStateOf<DockTransition?>(null)
}

private data class DockTransition(val from: DockDestination, val to: DockDestination) {
    // Put the origin in the slot beside the target. The target keeps its natural
    // index, so restoring the normal order at rest never moves the visible page.
    val startPage = to.ordinal - (to.ordinal - from.ordinal).sign

    fun destinationAt(page: Int): DockDestination = when (page) {
        startPage -> from
        from.ordinal -> DockDestination.entries[startPage]
        else -> DockDestination.entries[page]
    }
}

internal enum class DockDestination(val label: String) {
    ALBUM("相册"),
    HEATMAP("热力图"),
    AGENT("Agent"),
    SETTINGS("设置"),
}

internal fun Context.openDockDestination(destination: DockDestination) {
    startActivity(
        Intent(this, MainActivity::class.java)
            .putDockDestination(destination)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
}

internal fun Intent.putDockDestination(destination: DockDestination): Intent =
    putExtra(EXTRA_DOCK_DESTINATION, destination.name)

internal fun Intent.dockDestination(): DockDestination =
    getStringExtra(EXTRA_DOCK_DESTINATION)
        ?.let { name -> DockDestination.entries.firstOrNull { it.name == name } }
        ?: DockDestination.ALBUM

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppDockPager(
    requestedDestination: DockDestination,
    onExit: () -> Unit,
    onDestinationSettled: (DockDestination) -> Unit,
    pageContent: @Composable (DockDestination, (DockDestination) -> Unit) -> Unit,
) {
    val destinations = DockDestination.entries
    val pagerState = rememberPagerState(
        initialPage = requestedDestination.ordinal,
        pageCount = { destinations.size },
    )
    val scope = rememberCoroutineScope()
    val navigationState = remember { PagerNavigationState() }
    val navigate: (DockDestination) -> Unit = remember(pagerState, scope, navigationState) {
        { destination ->
            navigationState.job?.cancel()
            val previous = navigationState.transition
            if (previous?.to != destination &&
                (destination.ordinal != pagerState.settledPage || pagerState.isScrollInProgress || previous != null)
            ) {
                navigationState.job = scope.launch {
                    if (previous == null && pagerState.isScrollInProgress) {
                        snapshotFlow { pagerState.isScrollInProgress }.first { !it }
                    }
                    val from = previous?.let { if (pagerState.currentPage == it.to.ordinal) it.to else it.from }
                        ?: destinations[pagerState.currentPage]
                    navigationState.transition = DockTransition(from, destination)
                }
            }
        }
    }
    val transition = navigationState.transition
    val selected = transition?.let { if (pagerState.currentPage == it.to.ordinal) it.to else it.from }
        ?: destinations[pagerState.currentPage]

    // Start after composition has installed the endpoint keys in the pager.
    // A new tap cancels this effect and installs its own endpoint pair.
    LaunchedEffect(transition) {
        val move = transition ?: return@LaunchedEffect
        pagerState.scrollToPage(move.startPage)
        if (move.from != move.to) {
            pagerState.animateScrollToPage(move.to.ordinal, animationSpec = PAGER_SETTLE_ANIMATION_SPEC)
        }
        navigationState.transition = null
    }
    LaunchedEffect(requestedDestination) {
        if (requestedDestination.ordinal != pagerState.settledPage) navigate(requestedDestination)
    }
    val onSettled by rememberUpdatedState(onDestinationSettled)
    LaunchedEffect(pagerState) {
        snapshotFlow {
            if (navigationState.transition == null && !pagerState.isScrollInProgress) destinations[pagerState.settledPage]
            else null
        }.filterNotNull().collect { onSettled(it) }
    }
    BackHandler {
        if (selected == DockDestination.ALBUM && transition == null) {
            onExit()
        } else {
            navigate(DockDestination.ALBUM)
        }
    }

    Scaffold(
        modifier = Modifier.testTag("dock_pager_host"),
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AppBottomDock(
                selected = selected,
                onNavigate = navigate,
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = transition == null,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                pagerSnapDistance = PagerSnapDistance.atMost(1),
                snapAnimationSpec = PAGER_SETTLE_ANIMATION_SPEC,
                snapPositionalThreshold = PAGER_SNAP_POSITIONAL_THRESHOLD,
            ),
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
                .testTag("dock_horizontal_pager")
                .semantics {
                    contentDescription = "平滑横向分页，当前${selected.label}页面"
                },
            key = { page -> (transition?.destinationAt(page) ?: destinations[page]).name },
        ) { page ->
            val move = navigationState.transition
            val destination = move?.destinationAt(page) ?: destinations[page]
            val inViewport by remember(pagerState, page) {
                derivedStateOf { abs(pagerState.currentPage - page + pagerState.currentPageOffsetFraction) < 1f }
            }
            // Lazy pager can precompose a speculative slot. Only actual gesture
            // surfaces or the two requested endpoints may mount a page body.
            if (move != null && destination != move.from && destination != move.to) return@HorizontalPager
            if (move == null && !inViewport) return@HorizontalPager
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(destination.pageTestTag())
                    .semantics {
                        contentDescription = "${destination.label}页面，左右滑动平滑切换 Dock 页面"
                    },
            ) {
                pageContent(destination, navigate)
            }
        }
    }
}

@Composable
internal fun AppBottomDock(
    selected: DockDestination,
    onNavigate: (DockDestination) -> Unit,
) {
    val destinations = DockDestination.entries
    NavigationBar(
        modifier = Modifier
            .testTag("bottom_dock")
            .semantics {
                contentDescription = "固定底部 Dock：相册、热力图、Agent、设置；页面跟随左右滑动并平滑切换"
            },
    ) {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onNavigate(destination) },
                icon = {
                    Icon(
                        imageVector = when (destination) {
                            DockDestination.ALBUM -> Icons.Outlined.PhotoLibrary
                            DockDestination.HEATMAP -> Icons.Outlined.GridView
                            DockDestination.AGENT -> Icons.Outlined.SmartToy
                            DockDestination.SETTINGS -> Icons.Outlined.Settings
                        },
                        contentDescription = when (destination) {
                            DockDestination.ALBUM -> "相册首页"
                            DockDestination.HEATMAP -> "年度热力图"
                            DockDestination.AGENT -> "Agent"
                            DockDestination.SETTINGS -> "模型设置"
                        },
                    )
                },
                label = { Text(destination.label) },
                modifier = Modifier
                    .testTag(destination.testTag())
                    .semantics { contentDescription = destination.contentDescription() },
            )
        }
    }
}

private fun DockDestination.pageTestTag(): String = when (this) {
    DockDestination.ALBUM -> "dock_page_swipe_album"
    DockDestination.HEATMAP -> "dock_page_swipe_heatmap"
    DockDestination.AGENT -> "dock_page_swipe_agent"
    DockDestination.SETTINGS -> "dock_page_swipe_settings"
}

private fun DockDestination.testTag(): String = when (this) {
    DockDestination.ALBUM -> "dock_album"
    DockDestination.HEATMAP -> "dock_heatmap"
    DockDestination.AGENT -> "dock_agent"
    DockDestination.SETTINGS -> "dock_settings"
}

private fun DockDestination.contentDescription(): String = when (this) {
    DockDestination.ALBUM -> "相册首页"
    DockDestination.HEATMAP -> "年度热力图"
    DockDestination.AGENT -> "Agent"
    DockDestination.SETTINGS -> "模型设置"
}
