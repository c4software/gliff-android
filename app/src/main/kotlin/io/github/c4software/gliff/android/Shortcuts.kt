package io.github.c4software.gliff.android

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import io.github.c4software.gliff.domain.machine.Machine

/**
 * The saved machines under a long press on the launcher's icon, each one
 * opening its session. As many as the launcher takes, in the list's order.
 */
internal fun publishShortcuts(context: Context, machines: List<Machine>) {
    val manager = context.getSystemService(ShortcutManager::class.java) ?: return
    val shortcuts = machines.take(manager.maxShortcutCountPerActivity).mapIndexed { rank, machine ->
        ShortcutInfo.Builder(context, machine.id)
            .setShortLabel(machine.host)
            .setLongLabel(machine.address)
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setRank(rank)
            // A shortcut's intent needs an action; the session only reads the address.
            .setIntent(SessionActivity.intent(context, machine.id).setAction(Intent.ACTION_VIEW))
            .build()
    }
    // A launcher that refuses them is no reason to stop the app.
    runCatching { manager.dynamicShortcuts = shortcuts }
}
