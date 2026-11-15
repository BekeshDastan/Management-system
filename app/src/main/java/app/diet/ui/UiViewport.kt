package app.diet.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class ViewportLoad {
    val readers = mutableStateMapOf<Any, Boolean>()
    val loading get() = readers.values.any { it }
    var restored by mutableStateOf(false)
}
private val LocalViewportLoad = staticCompositionLocalOf<ViewportLoad?> { null }

/** A returning async screen must finish its first read before restoring its saved offset. */
@Composable internal fun ScreenLoad(loading: Boolean) {
    val viewport = LocalViewportLoad.current ?: return
    val token = remember { Any() }
    SideEffect { viewport.readers[token] = loading }
    DisposableEffect(viewport, token) { onDispose { viewport.readers.remove(token) } }
}

@Composable internal fun RetainedViewport(key: String, content: @Composable ColumnScope.() -> Unit) {
    var savedIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedOffset by rememberSaveable { mutableIntStateOf(0) }
    // Deliberately start empty: LazyListState can clamp a restored offset against a loading stub.
    val list = rememberLazyListState()
    var target by remember { mutableStateOf(savedIndex to savedOffset) }
    val h = LocalUi.current
    var resetVersion by remember { mutableLongStateOf(h.scrollResetVersion) }
    val loading = remember { ViewportLoad() }
    var restored by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(true) }
    val reduced = LocalReducedMotion.current
    val entrance = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(loading.loading, h.scrollResetVersion) {
        settling = true
        if (resetVersion != h.scrollResetVersion) {
            resetVersion = h.scrollResetVersion
            target = 0 to 0; savedIndex = 0; savedOffset = 0
        } else if (loading.loading && restored) {
            target = savedIndex to savedOffset
        }
        if (!loading.loading) {
            withFrameNanos { }; withFrameNanos { }
            if (!loading.loading) {
                val anchor = h.returnAnchor?.takeIf { it.screen == key }
                // User input may cancel a LazyList mutation. Keep that cancellation in a
                // child so it cannot strand the viewport before revealing its content.
                // Cancelling this effect for a new load still cancels the child normally.
                launch {
                    list.scrollToItem(target.first, target.second)
                    if (anchor != null) {
                        withFrameNanos { }; withFrameNanos { }
                        h.anchorPositions[anchor.key]?.let { current -> list.scrollBy(current - anchor.y) }
                    }
                }.join()
                if (anchor != null && h.returnAnchor === anchor) h.returnAnchor = null
                restored = true
                loading.restored = true
                settling = false
                entrance.animateTo(1f, tween(if (reduced) 0 else 160))
            }
        }
    }
    LaunchedEffect(list, restored) {
        if (restored) snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, (loading.loading || settling)) }
            .distinctUntilChanged().collect { (index, offset, busy) ->
                if (!busy) { savedIndex = index; savedOffset = offset; target = index to offset }
            }
    }
    CompositionLocalProvider(LocalViewportLoad provides loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 760.dp).fillMaxHeight().imePadding().testTag("viewport-" + key)
                    .graphicsLayer { alpha = if (!restored && target.second > 0) 0f else entrance.value; translationY = if (reduced) 0f else (1f - entrance.value) * 10.dp.toPx() },
                state = list,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
            }
            if (!restored && target.second > 0) CircularProgressIndicator(Modifier.padding(28.dp))
        }
    }
}

@Composable internal fun Modifier.mealAnchor(id: String): Modifier {
    val h = LocalUi.current
    val viewport = LocalViewportLoad.current
    val requester = remember { BringIntoViewRequester() }
    val headerHeight = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(h.focusMealId, id) {
        if (h.focusMealId == id) {
            if (viewport != null) snapshotFlow { viewport.restored && !viewport.loading }.first { it }
            withFrameNanos { }; withFrameNanos { }
            requester.bringIntoView(Rect(0f, 0f, 1f, headerHeight))
            if (h.focusMealId == id) h.focusMealId = null
        }
    }
    return bringIntoViewRequester(requester)
}


@Composable internal fun Modifier.returnAnchor(kind: String): Modifier {
    val h = LocalUi.current
    val key = h.screenKey + ":" + kind
    DisposableEffect(key) { onDispose { h.anchorPositions.remove(key) } }
    return onGloballyPositioned { h.anchorPositions[key] = it.positionInRoot().y }
}
