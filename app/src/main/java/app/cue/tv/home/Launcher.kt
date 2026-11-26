package app.cue.tv.home

import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Cue Home's home screen: what you were watching, big, at the top (from Cue
// or from Netflix and the other apps), then Continue watching, Live TV on
// now, your streaming services, your apps, and Cue's rows for you. The
// remote's Home button always comes back here (HomeSignal).

/** The Home button was pressed: the main screen goes back to Home. */
object HomeSignal {
    var presses by mutableIntStateOf(0)
        private set

    fun press() {
        presses++
    }
}

// One thing in Continue watching: a Cue title, or another app's.
private sealed interface Resume {
    val key: String
    val title: String
    val line: String // the episode, or how long's left
    val image: String?
    val progress: Float?

    data class FromCue(val tile: Tile) : Resume {
        override val key get() = "cue-" + tile.key
        override val title get() = tile.title
        override val line get() = tile.episodeLine.ifEmpty { if (tile.kind == "tv") "Series" else "Film" }
        override val image get() = small(tile.backdrop) ?: tile.poster
        override val progress get() = tile.progress?.toFloat()
    }

    data class FromApp(val next: AppNext, val app: String) : Resume {
        override val key get() = next.key
        override val title get() = next.title
        override val line get() = listOfNotNull(next.episode.takeIf { it.isNotEmpty() }, next.minutesLeft?.let { left(it) }, app).joinToString(" · ")
        override val image get() = next.image
        override val progress get() = next.progress
    }
}

private fun left(min: Int): String = when {
    min < 1 -> "Ending"
    min < 60 -> "${min}m left"
    else -> "${min / 60}h ${min % 60}m left"
}

// Cue's own Continue watching has no time on it: other apps' watched in the
// last day come first, then Cue's, then the rest.
private fun mixed(cue: List<Tile>, apps: List<AppNext>, appName: (String) -> String): List<Resume> {
    val day = System.currentTimeMillis() - 24 * 3600_000L
    val (recent, older) = apps.partition { it.engaged >= day }
    return recent.map { Resume.FromApp(it, appName(it.pkg)) } + cue.map { Resume.FromCue(it) } + older.map { Resume.FromApp(it, appName(it.pkg)) }
}

private var launcherHomeCache: Home? = null
private var servicesCache: List<BrowseService>? = null
private var liveCache: List<Channel>? = null

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LauncherHome(
    api: CueApi,
    playback: Playback,
    memory: FocusMemory,
    takeFocus: Boolean,
    tookFocus: () -> Unit,
    setAmbient: (Tile?) -> Unit,
    onCard: (Tile) -> Unit,
    openService: (BrowseService) -> Unit,
    openLive: () -> Unit,
) {
    val ctx = LocalContext.current
    var home by remember { mutableStateOf(launcherHomeCache) }
    var services by remember { mutableStateOf(servicesCache ?: emptyList()) }
    var live by remember { mutableStateOf(liveCache ?: emptyList()) }
    var appsNext by remember { mutableStateOf<List<AppNext>>(emptyList()) }
    var apps by remember { mutableStateOf<List<TvApp>>(emptyList()) }
    // Read again each time Home comes back (an app was just watched).
    LaunchedEffect(HomeSignal.presses) {
        withContext(Dispatchers.IO) {
            apps = TvApps.list(ctx)
            appsNext = WatchNext.read(ctx)
        }
        runCatching { api.home() }.getOrNull()?.let { launcherHomeCache = it; home = it }
        if (services.isEmpty()) runCatching { api.browse().services }.getOrNull()?.let { servicesCache = it; services = it }
        runCatching { api.channels() }.getOrNull()?.takeIf { it.configured }?.let { l ->
            val on = l.channels.filter { !it.divider && !it.copy && it.now != null }
                .sortedWith(compareBy({ it.rank }, { if (it.num > 0) it.num else Int.MAX_VALUE })).take(16)
            liveCache = on
            live = on
        }
    }
    val names = remember(apps) { apps.associate { it.pkg to it.label } }
    val h = home
    val cueContinue = h?.rows?.firstOrNull { it.key == "continue" }?.items.orEmpty()
    val resume = remember(cueContinue, appsNext, names) { mixed(cueContinue, appsNext, { names[it] ?: "" }) }
    val forYou = h?.rows?.filter { it.key != "continue" }.orEmpty().take(4)

    // The top: whatever the highlight is on, settled (Continue watching
    // first, before anything is highlighted).
    var spot by remember { mutableStateOf<Resume?>(null) }
    val shown = spot ?: resume.firstOrNull()
    LaunchedEffect(shown?.key) {
        delay(250)
        setAmbient(
            when (shown) {
                is Resume.FromCue -> shown.tile
                is Resume.FromApp -> Tile("app", 0, shown.title, null, null, null, shown.image, null, shown.progress?.toDouble(), null, null, null, false)
                null -> h?.hero
            },
        )
    }
    // The highlight starts on Continue watching, so it waits for Cue's rows
    // (the apps are read quicker, and would otherwise take it).
    LaunchedEffect(h != null, takeFocus) {
        if (!takeFocus || h == null) return@LaunchedEffect
        delay(120)
        if (memory.restore(resume.firstOrNull()?.let { "resume/${it.key}" } ?: apps.firstOrNull()?.let { "apps/${it.key}" })) tookFocus()
    }

    // Rows arrive at different times (the apps first, Cue's a moment later):
    // until the remote is in the list, it stays at its top, so a row that
    // arrives above the others doesn't land out of sight.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var inList by remember { mutableStateOf(false) }
    LaunchedEffect(resume.size, live.size, services.size, apps.size, forYou.size) {
        if (!inList) listState.scrollToItem(0)
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(api)
        Hero(shown, h?.hero)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRowsSpec()) {
            LazyColumn(Modifier.fillMaxSize().onFocusChanged { inList = it.hasFocus }, state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp)) {
                if (resume.isNotEmpty()) item(key = "resume") {
                    Shelf("Continue watching", "${resume.size} in progress" + if (WatchNext.problem == "permission") " · Cue only (see Settings › Cue Home)" else "") {
                        items(resume, key = { it.key }) { r ->
                            ResumePill(r, memory, "resume/${r.key}", onFocus = { spot = r }) {
                                when (r) {
                                    is Resume.FromCue -> onCard(r.tile)
                                    is Resume.FromApp -> WatchNext.open(ctx, r.next)
                                }
                            }
                        }
                    }
                }
                if (live.isNotEmpty()) item(key = "live") {
                    Shelf("Live TV", "On now") {
                        item(key = "guide") { GuideTile(memory, "live/guide", onFocus = { spot = null }, onClick = openLive) }
                        items(live, key = { it.id }) { ch ->
                            ChannelPill(api, ch, memory, "live/${ch.id}", onFocus = { spot = null }) { playback.playLive(ch, live) }
                        }
                    }
                }
                if (services.isNotEmpty()) item(key = "services") {
                    Shelf("Your services", "What's on each") {
                        items(services, key = { it.key }) { s ->
                            ServiceCard(s, memory, "services/${s.key}", onFocus = { spot = null }) { openService(s) }
                        }
                    }
                }
                if (apps.isNotEmpty()) item(key = "apps") {
                    Shelf("Apps", null) {
                        items(apps.take(14), key = { it.key }) { a ->
                            AppBanner(a, memory, "apps/${a.key}", onFocus = { spot = null }) { TvApps.open(ctx, a.pkg) }
                        }
                    }
                }
                forYou.forEach { r ->
                    item(key = "cue-${r.key}") {
                        CardRow(r, memory, onFocus = { t -> spot = Resume.FromCue(t) }, onClick = onCard)
                    }
                }
            }
        }
    }
}

// The top line: the profile, the time.
@Composable
private fun TopBar(api: CueApi) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val ctx = LocalContext.current
    val clock = remember(now / 60_000) { android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(now)) }
    val day = remember(now / 3_600_000) { java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(java.util.Date(now)) }
    Row(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(LocalAccent.current), contentAlignment = Alignment.Center) {
            AvatarFace(api.profileAvatar, api.profileName, 14.sp)
        }
        HGap(10.dp)
        Text(api.profileName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Spacer(Modifier.weight(1f))
        Text(day, fontSize = 13.sp, color = Dim)
        HGap(14.dp)
        Text(clock, fontFamily = Type.display(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

// The big words at the top: CONTINUE, the title, where you are in it.
@Composable
private fun Hero(resume: Resume?, forYou: Tile?) {
    Box(Modifier.fillMaxWidth().height(176.dp).padding(horizontal = Gutter)) {
        AnimatedContent(
            targetState = resume to forYou,
            contentKey = { (r, h) -> r?.key ?: h?.key ?: "" },
            transitionSpec = { fadeIn(tween(260, delayMillis = 80, easing = Settle)) togetherWith fadeOut(tween(110)) },
            label = "launcher-hero",
            modifier = Modifier.align(Alignment.BottomStart),
        ) { (r, hero) ->
            Column(Modifier.padding(bottom = 14.dp).width(520.dp)) {
                val kicker = when (r) {
                    null -> if (hero != null) "FOR YOU" else ""
                    else -> "CONTINUE"
                }
                if (kicker.isNotEmpty()) Text(kicker, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = LocalAccent.current)
                Gap(4.dp)
                Text(r?.title ?: hero?.title.orEmpty(), fontFamily = Type.display(), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = r?.line ?: hero?.overview.orEmpty()
                if (line.isNotEmpty()) {
                    Gap(6.dp)
                    Text(line, fontSize = 13.sp, color = Color(0xD9FFFFFF), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                r?.progress?.let { p ->
                    Gap(10.dp)
                    Box(Modifier.width(220.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
                        Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(Color.White))
                    }
                }
            }
        }
    }
}

// A row's name (and a few words beside it), then its things, sideways.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Shelf(title: String, note: String?, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(Modifier.padding(start = Gutter + 2.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xF2FFFFFF))
            note?.let {
                HGap(10.dp)
                Text(it, fontSize = 11.sp, color = Dim)
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides PivotSpec) {
            LazyRow(
                modifier = Modifier.keepRowFocus(),
                contentPadding = PaddingValues(horizontal = Gutter, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

// A highlightable card: white edge and a lift when the highlight is on it.
@Composable
private fun LaunchCard(
    memory: FocusMemory, memKey: String, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, round: androidx.compose.ui.unit.Dp,
    onFocus: () -> Unit, onClick: () -> Unit, fill: Color = Color(0x1FFFFFFF), focusedFill: Color = Color(0x33FFFFFF),
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(round)
    Card(
        onClick = onClick,
        modifier = Modifier.size(width, height).zIndex(if (focused) 1f else 0f)
            .lift(focused, 1.06f, shape)
            .focusRequester(memory.requester(memKey))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    memory.key = memKey
                    onFocus()
                }
            },
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f),
        colors = CardDefaults.colors(containerColor = fill, focusedContainerColor = focusedFill),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, Color(0x14FFFFFF)), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape),
        ),
    ) { Box(Modifier.fillMaxSize()) { content(focused) } }
}

// Continue watching: a rounded pill, its picture at the left, the name and
// where you are beside it; white when highlighted.
@Composable
private fun ResumePill(r: Resume, memory: FocusMemory, memKey: String, onFocus: () -> Unit, onClick: () -> Unit) {
    LaunchCard(memory, memKey, 280.dp, 66.dp, 33.dp, onFocus, onClick, focusedFill = Color(0xF2FFFFFF)) { focused ->
        Row(Modifier.fillMaxSize().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(27.dp)).background(Panel)) {
                r.image?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                r.progress?.let { p ->
                    Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().height(3.dp)
                        .clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF))) {
                        Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(Color.White))
                    }
                }
            }
            HGap(12.dp)
            Column(Modifier.weight(1f)) {
                Text(r.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Color(0xFF0B0B0C) else Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.line, fontSize = 10.sp, color = if (focused) Color(0xFF52525B) else Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HGap(8.dp)
        }
    }
}

// The full TV guide, first in the Live TV row.
@Composable
private fun GuideTile(memory: FocusMemory, memKey: String, onFocus: () -> Unit, onClick: () -> Unit) {
    LaunchCard(memory, memKey, 150.dp, 84.dp, 16.dp, onFocus, onClick, fill = Color(0xFF3A1318), focusedFill = Color(0xFF5A1C24)) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Bottom) {
            Text("● LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF6B6B))
            Text("TV Guide", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

// A channel on now: its logo, its name and what's on.
@Composable
private fun ChannelPill(api: CueApi, ch: Channel, memory: FocusMemory, memKey: String, onFocus: () -> Unit, onClick: () -> Unit) {
    LaunchCard(memory, memKey, 250.dp, 84.dp, 16.dp, onFocus, onClick) {
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp, 40.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x14FFFFFF))) {
                ChannelLogo(api, ch, Modifier.fillMaxSize().padding(4.dp))
            }
            HGap(10.dp)
            Column(Modifier.weight(1f)) {
                Text(ch.name, fontSize = 11.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ch.now?.title.orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            }
        }
    }
}

// A streaming service: its logo (its page in Cue opens: what's on it, and
// Open the app).
@Composable
private fun ServiceCard(s: BrowseService, memory: FocusMemory, memKey: String, onFocus: () -> Unit, onClick: () -> Unit) {
    LaunchCard(memory, memKey, 150.dp, 84.dp, 16.dp, onFocus, onClick, fill = Color(0xFF111113), focusedFill = Color(0xFF1C1C1F)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (s.logo != null) {
                AsyncImage(model = s.logo, contentDescription = s.name, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
            } else {
                Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

// An app: its TV banner (or its icon and name).
@Composable
private fun AppBanner(a: TvApp, memory: FocusMemory, memKey: String, onFocus: () -> Unit, onClick: () -> Unit) {
    LaunchCard(memory, memKey, 150.dp, 84.dp, 14.dp, onFocus, onClick, fill = Color(0xFF1B1B1E)) {
        val banner = remember(a.pkg) { a.banner?.let { bitmap(it, 320, 180) } }
        val icon = remember(a.pkg) { if (banner == null) a.icon?.let { bitmap(it, 96, 96) } else null }
        when {
            banner != null -> Image(banner, contentDescription = a.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp)) }
                Gap(6.dp)
                Text(a.label, fontSize = 11.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun bitmap(d: Drawable, w: Int, h: Int) = runCatching { d.toBitmap(w, h).asImageBitmap() }.getOrNull()

// Apps: everything on the TV.
@Composable
fun AppsScreen(memory: FocusMemory, takeFocus: Boolean, tookFocus: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<TvApp>?>(null) }
    LaunchedEffect(Unit) {
        TvApps.refresh()
        apps = withContext(Dispatchers.IO) { TvApps.list(ctx) }
    }
    val list = apps
    LaunchedEffect(list != null, takeFocus) {
        if (!takeFocus || list.isNullOrEmpty()) return@LaunchedEffect
        delay(80)
        if (memory.restore("all/${list.first().key}")) tookFocus()
    }
    if (list == null) {
        Centered { Text("Loading…", color = Dim) }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text("Apps", fontFamily = Type.display(), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.padding(start = Gutter, top = 24.dp, bottom = 4.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 14.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(list.size, key = { list[it].key }) { i ->
                val a = list[i]
                Column {
                    AppBanner(a, memory, "all/${a.key}", onFocus = {}) { TvApps.open(ctx, a.pkg) }
                    Gap(6.dp)
                    Text(a.label, fontSize = 11.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
