package com.sami.assistant.system

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sami.assistant.MainActivity
import com.sami.assistant.core.SamiPrefs

/** Quick Settings tile: tap to turn "Hey Sami" listening on/off. */
class SamiTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val prefs = SamiPrefs(this)
        val on = !prefs.wakeEnabled
        prefs.wakeEnabled = on
        if (!on) { WakeWordService.stop(this); refresh(); return }
        // Starting a microphone service is allowed from a visible activity; the tile briefly opens Sami, which starts it.
        if (!WakeWordService.start(this)) {
            val i = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_START_WAKE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(PendingIntent.getActivity(this, 5, i, PendingIntent.FLAG_IMMUTABLE))
        }
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = SamiPrefs(this).wakeEnabled
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Hey Sami"
        t.subtitle = if (on) "Listening" else "Off"
        t.updateTile()
    }
}
