package com.davenn.carbuddy

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap

/** An installed app that can be opened on the car display. */
data class LaunchableApp(val label: String, val component: String, val packageName: String)

/** Lists launchable apps, most recently opened on the car first. */
object Apps {

    private const val PREFS = "carbuddy"
    private const val KEY_RECENT = "recent"
    private const val MAX_RECENT = 8

    fun list(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val all = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                LaunchableApp(
                    label = it.loadLabel(pm).toString(),
                    component = "${it.activityInfo.packageName}/${it.activityInfo.name}",
                    packageName = it.activityInfo.packageName,
                )
            }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }

        val recent = recent(context)
        return all.sortedBy { app -> recent.indexOf(app.component).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    fun icon(context: Context, app: LaunchableApp, sizePx: Int = 96): Bitmap? = try {
        context.packageManager.getApplicationIcon(app.packageName).toBitmap(sizePx, sizePx)
    } catch (_: Exception) {
        null
    }

    fun markOpened(context: Context, app: LaunchableApp) {
        val list = (listOf(app.component) + recent(context).filter { it != app.component })
            .take(MAX_RECENT)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECENT, list.joinToString("\n")).apply()
    }

    private fun recent(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT, "")!!
            .split("\n").filter { it.isNotBlank() }
}
