package io.github.korenkrita.simulsub

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 通知栏快捷按钮: the Quick Settings tile that starts the captions from the shade's 添加 list — every service declared with
 * the QS_TILE intent filter is listed there after install, so nothing in the app has to add it — and stops the running
 * ones on the next tap. It has no state of its own: the highlight follows [CaptionService.running], the same flag the
 * home card and the notification read.
 *
 * A tap cannot start a session by itself: MediaProjection asks for its consent per session, and that dialog needs an
 * activity. So an idle tap opens [MainActivity] with [EXTRA_START_CAPTIONS], and the home screen's own start chain does
 * the rest (BYOK check → the two permissions → the consent dialog → [CaptionService.start] → moveTaskToBack). A start
 * from the tile and one from the card therefore run the same code, and the tile cannot start where the card would ask
 * the user to fill something in first.
 */
class CaptionTileService : TileService() {
    /** The shade opened: say whether a session runs, so the tile never shows a stale state. */
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (CaptionService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (CaptionService.running) {
            // the card's 停止字幕: the overlay and this app's notification go away with the service
            CaptionService.stop(this)
            // the flag only clears in the service's onDestroy, which is after this callback; the shade is open now
            qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
            return
        }
        val i = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_START_CAPTIONS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            // from 34 on the Intent overload is deprecated; both forms show the activity as the shade collapses
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    companion object {
        /** Read once and removed by [MainActivity]: a request, never a state the screen keeps. */
        const val EXTRA_START_CAPTIONS = "io.github.korenkrita.simulsub.extra.START_CAPTIONS"

        /**
         * Ask SystemUI to run [onStartListening] again. The highlight follows [CaptionService.running], which changes
         * while the shade is closed (the consent dialog's 允许, the stop) — without this the tile stays as it looked when
         * the shade was last open, so it never lights up after a start. Called by [CaptionService] when the flag
         * changes; one binder call, safe from a service.
         *
         * Guarded: the platform may throw SecurityException / IllegalArgumentException for a context whose user is not
         * the current one on TIRAMISU+ (see TileService.requestListeningState), and a stale highlight must never take
         * the session down. A failure only leaves the tile as it was until the next shade open, which re-reads it.
         */
        fun requestUpdate(context: Context) {
            runCatching {
                TileService.requestListeningState(context, android.content.ComponentName(context, CaptionTileService::class.java))
            }
        }
    }
}
