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
import androidx.compose.material.icons.outlined.Face
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val EXTRA_DOCK_DESTINATION = "apm_dock_destination"
private const val PAGER_SNAP_POSITIONAL_THRESHOLD = 0.32f
private val PAGER_SETTLE_ANIMATION_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 650f,
)
private class PagerNavigationState(var job: Job? = null)

internal enum class DockDestination(val label: String) {
    ALBUM("相册"),
    HEATMAP("热力图"),
    PEOPLE("人"),
    AGENT("Agent"),
    SETTINGS("设置"),
}

internal fun Context.openDockDestination(destination: DockDestination) {
    startActivity(
        Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_DOCK_DESTINATION, destination.name)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
}

internal fun Intent.dockDestination(): DockDestination =
    getStringExtra(EXTRA_DOCK_DESTINATION)
        ?.let { name -> DockDestination.entries.firstOrNull { it.name == name } }
        ?: DockDestination.ALBUM

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppDockPager(
    requestedDestination: DockDestination,
    busy: Boolean,
    searchEnabled: Boolean,
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
            if (destination.ordinal != pagerState.settledPage || pagerState.isScrollInProgress) {
                val replacesProgrammaticSettle = navigationState.job?.isActive == true
                navigationState.job?.cancel()
                navigationState.job = scope.launch {
                    if (!replacesProgrammaticSettle && pagerState.isScrollInProgress) {
                        snapshotFlow { pagerState.isScrollInProgress }
                            .first { inProgress -> !inProgress }
                    }
                    if (destination.ordinal != pagerState.settledPage) {
                        pagerState.animateScrollToPage(
                            page = destination.ordinal,
                            animationSpec = PAGER_SETTLE_ANIMATION_SPEC,
                        )
                    }
                }
            }
        }
    }
    val selected = destinations[pagerState.currentPage]
    val settled = destinations[pagerState.settledPage]

    LaunchedEffect(requestedDestination) {
        if (requestedDestination.ordinal != pagerState.settledPage) {
            navigate(requestedDestination)
        }
    }
    LaunchedEffect(settled) { onDestinationSettled(settled) }
    BackHandler(enabled = pagerState.currentPage != DockDestination.ALBUM.ordinal) {
        navigate(DockDestination.ALBUM)
    }

    Scaffold(
        modifier = Modifier.testTag("dock_pager_host"),
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AppBottomDock(
                selected = selected,
                busy = busy,
                searchEnabled = searchEnabled,
                onNavigate = navigate,
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
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
            beyondBoundsPageCount = 1,
            key = { destinations[it].name },
        ) { page ->
            val destination = destinations[page]
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
    busy: Boolean,
    searchEnabled: Boolean,
    onNavigate: (DockDestination) -> Unit,
) {
    val destinations = DockDestination.entries
    NavigationBar(
        modifier = Modifier
            .testTag("bottom_dock")
            .semantics {
                contentDescription = "固定底部 Dock：相册、热力图、人、Agent、设置；页面跟随左右滑动并平滑切换"
            },
    ) {
        destinations.forEach { destination ->
            val enabled = when (destination) {
                DockDestination.PEOPLE -> !busy
                DockDestination.AGENT -> searchEnabled && !busy
                else -> true
            }
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onNavigate(destination) },
                enabled = enabled,
                icon = {
                    Icon(
                        imageVector = when (destination) {
                            DockDestination.ALBUM -> Icons.Outlined.PhotoLibrary
                            DockDestination.HEATMAP -> Icons.Outlined.GridView
                            DockDestination.PEOPLE -> Icons.Outlined.Face
                            DockDestination.AGENT -> Icons.Outlined.SmartToy
                            DockDestination.SETTINGS -> Icons.Outlined.Settings
                        },
                        contentDescription = when (destination) {
                            DockDestination.ALBUM -> "相册首页"
                            DockDestination.HEATMAP -> "年度热力图"
                            DockDestination.PEOPLE -> "人物与宠物"
                            DockDestination.AGENT -> "Agent 搜索"
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
    DockDestination.PEOPLE -> "dock_page_swipe_people"
    DockDestination.AGENT -> "dock_page_swipe_agent"
    DockDestination.SETTINGS -> "dock_page_swipe_settings"
}

private fun DockDestination.testTag(): String = when (this) {
    DockDestination.ALBUM -> "dock_album"
    DockDestination.HEATMAP -> "dock_heatmap"
    DockDestination.PEOPLE -> "dock_identity"
    DockDestination.AGENT -> "dock_agent"
    DockDestination.SETTINGS -> "dock_settings"
}

private fun DockDestination.contentDescription(): String = when (this) {
    DockDestination.ALBUM -> "相册首页"
    DockDestination.HEATMAP -> "年度热力图"
    DockDestination.PEOPLE -> "人物与宠物"
    DockDestination.AGENT -> "Agent 搜索"
    DockDestination.SETTINGS -> "模型设置"
}
