package com.sami.assistant.system

import android.app.PendingIntent
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * "Sami screen control" Quick Settings tile. AOSP toggles the accessibility service itself for tiles declared via
 * android:tileService, but OxygenOS 16 doesn't — so: tap = turn off directly (allowed), or open Accessibility to turn on.
 */
class ScreenControlTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = if (SamiAccessibilityService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; label = "Sami screen control"; updateTile() }
    }

    override fun onClick() {
        SamiAccessibilityService.instance?.let { it.disableSelf(); qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }; return }
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
    }
}
