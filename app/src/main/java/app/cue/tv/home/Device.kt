package app.cue.tv.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log

// What Cue Home reads from the TV itself: the apps on it, and what the
// other apps say you're in the middle of (Android's Watch Next list).

// ---- Watch Next: Continue watching from Netflix and the rest ----

/** An entry another app put on Watch Next: a film or episode you're in the middle of (or its next one). */
data class AppNext(
    val id: Long,
    val pkg: String,
    val title: String,
    val episode: String, // "S2:E5 · Name", or ""
    val image: String?, // its picture (often a content:// address, which the picture loader reads)
    val progress: Float?, // 0 to 1, when the app says where you are
    val minutesLeft: Int?,
    val engaged: Long, // when you last watched it (ms)
    val intentUri: String?, // what opens it in its app
) {
    val key get() = "next-$pkg-$id"
}

object WatchNext {
    /** "permission": the TV won't let Cue Home read the list (scripts/ grants it); "": fine. */
    var problem = ""
        private set

    /** What's on Watch Next, most recently watched first; empty where the TV doesn't allow it. */
    fun read(ctx: Context): List<AppNext> {
        if (Build.VERSION.SDK_INT < 26) return emptyList()
        val out = ArrayList<AppNext>()
        val uri = android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                fun str(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }
                fun num(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { if (c.isNull(it)) null else c.getLong(it) }
                while (c.moveToNext()) {
                    val pkg = str("package_name") ?: continue
                    if (pkg == ctx.packageName) continue
                    if (num("browsable") == 0L) continue
                    val title = str("title")?.takeIf { it.isNotBlank() } ?: continue
                    val season = str("season_display_number")
                    val ep = str("episode_display_number")
                    val epTitle = str("episode_title")
                    val episode = listOfNotNull(
                        if (season != null && ep != null) "S$season:E$ep" else ep?.let { "E$it" },
                        epTitle?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    val pos = num("last_playback_position_millis") ?: 0L
                    val dur = num("duration_millis") ?: 0L
                    out += AppNext(
                        id = num("_id") ?: 0L,
                        pkg = pkg,
                        title = title,
                        episode = episode,
                        image = str("thumbnail_uri") ?: str("poster_art_uri"),
                        progress = if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null,
                        minutesLeft = if (pos > 0 && dur > pos) ((dur - pos) / 60_000L).toInt() else null,
                        engaged = num("last_engagement_time_utc_millis") ?: 0L,
                        intentUri = str("intent_uri"),
                    )
                }
            }
            problem = ""
        } catch (e: SecurityException) {
            problem = "permission"
        } catch (e: Exception) {
            Log.w("CueHome", "watch next", e)
        }
        return out.sortedByDescending { it.engaged }.distinctBy { it.pkg + "|" + it.title }
    }

    /** Opens the entry in its app (where it says), else the app itself. */
    fun open(ctx: Context, n: AppNext): Boolean {
        val tries = buildList {
            n.intentUri?.let { u -> runCatching { Intent.parseUri(u, Intent.URI_INTENT_SCHEME) }.getOrNull()?.let { add(it) } }
            ctx.packageManager.getLeanbackLaunchIntentForPackage(n.pkg)?.let { add(it) }
            ctx.packageManager.getLaunchIntentForPackage(n.pkg)?.let { add(it) }
        }
        for (i in tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) return true
        }
        return false
    }
}
// ---- The apps on the TV ----

data class TvApp(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable?) {
    val key get() = "app-$pkg"
}

object TvApps {
    private var cache: List<TvApp>? = null

    // The streaming services first (as on the TV's own home), then A to Z.
    private val first = listOf(
        "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus",
        "com.wbd.stream", "com.hulu.livingroomplus", "com.apple.atve.androidtv.appletv", "com.peacocktv.peacockandroid",
        "com.cbs.ott", "com.google.android.youtube.tvunplugged", "com.plexapp.android", "com.tubitv", "tv.pluto.android",
    )

    /** The TV's apps (those made for TVs), without Cue Home itself. Read once; [refresh] after an install. */
    fun list(ctx: Context): List<TvApp> {
        cache?.let { return it }
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, TvApp>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            val infos = runCatching {
                if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(q, PackageManager.ResolveInfoFlags.of(0)) else @Suppress("DEPRECATION") pm.queryIntentActivities(q, 0)
            }.getOrDefault(emptyList())
            for (ri in infos) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == ctx.packageName || found.containsKey(pkg)) continue
                // On a TV, only apps made for it (phone-only apps don't work with a remote).
                if (cat == Intent.CATEGORY_LAUNCHER && isTv(ctx)) continue
                found[pkg] = TvApp(
                    pkg = pkg,
                    label = ri.loadLabel(pm)?.toString() ?: pkg,
                    banner = runCatching { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) }.getOrNull(),
                    icon = runCatching { ri.loadIcon(pm) }.getOrNull(),
                )
            }
        }
        val apps = found.values.sortedWith(compareBy({ first.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.label.lowercase() }))
        cache = apps
        return apps
    }

    fun refresh() {
        cache = null
    }

    fun open(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    private fun isTv(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
