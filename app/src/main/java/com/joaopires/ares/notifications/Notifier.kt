// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.joaopires.ares.MainActivity
import com.joaopires.ares.R
import com.joaopires.ares.core.data.EntryEvent
import com.joaopires.ares.core.data.EntryEvents
import com.joaopires.ares.core.data.EntryRepository
import com.joaopires.ares.core.data.model.NewEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

const val CHANNEL_NEW_ENTRIES = "new_entries"
const val ACTION_OPEN_ENTRY = "com.joaopires.ares.action.OPEN_ENTRY"
const val ACTION_MARK_READ = "com.joaopires.ares.action.MARK_READ"
const val ACTION_BOOKMARK = "com.joaopires.ares.action.BOOKMARK"
const val EXTRA_ENTRY_ID = "com.joaopires.ares.extra.ENTRY_ID"
const val MAX_ENTRY_NOTIFICATIONS = 10
const val ENTRY_NOTIFICATION_ID = 1
const val SUMMARY_NOTIFICATION_ID = 2
const val SUMMARY_TAG = "summary"
private const val GROUP_NEW_ENTRIES = "com.joaopires.ares.NEW_ENTRIES"
private const val SUMMARY_LINES = 5

class Notifier(private val context: Context, private val entries: EntryRepository) {

    private val manager = NotificationManagerCompat.from(context)

    fun canPost(): Boolean = notificationsAllowed(context)

    fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_NEW_ENTRIES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.notification_channel_new_entries))
                .setDescription(context.getString(R.string.notification_channel_new_entries_description))
                .build(),
        )
    }

    @SuppressLint("MissingPermission")
    fun post(newEntries: List<NewEntry>) {
        if (newEntries.isEmpty() || !canPost()) return
        ensureChannel()
        newEntries.take(MAX_ENTRY_NOTIFICATIONS).forEach { entry ->
            manager.notify(entry.id, ENTRY_NOTIFICATION_ID, entryNotification(entry))
        }
        manager.notify(SUMMARY_TAG, SUMMARY_NOTIFICATION_ID, summaryNotification(newEntries))
    }

    fun cancel(entryIds: Collection<String>) {
        val active = manager.activeNotifications
        val shown = active.filter { it.id == ENTRY_NOTIFICATION_ID }.mapNotNullTo(HashSet()) { it.tag }
        val toCancel = entryIds.filter { it in shown }
        toCancel.forEach { manager.cancel(it, ENTRY_NOTIFICATION_ID) }
        val summaryShown = active.any { it.id == SUMMARY_NOTIFICATION_ID && it.tag == SUMMARY_TAG }
        if (summaryShown && (shown - toCancel.toSet()).isEmpty()) manager.cancel(SUMMARY_TAG, SUMMARY_NOTIFICATION_ID)
    }

    fun cancelAll() {
        val active = manager.activeNotifications
        active.filter { it.id == ENTRY_NOTIFICATION_ID }.mapNotNull { it.tag }.forEach { manager.cancel(it, ENTRY_NOTIFICATION_ID) }
        if (active.any { it.id == SUMMARY_NOTIFICATION_ID && it.tag == SUMMARY_TAG }) manager.cancel(SUMMARY_TAG, SUMMARY_NOTIFICATION_ID)
    }

    suspend fun removeMissing() {
        val active = activeEntryIds()
        if (active.isEmpty()) return
        cancel(active - entries.existingIds(active))
    }

    fun listen(scope: CoroutineScope, events: EntryEvents) {
        scope.launch {
            events.events.collect { event ->
                when (event) {
                    is EntryEvent.MarkedRead -> cancel(event.entryIds)
                    EntryEvent.Removed -> removeMissing()
                }
            }
        }
    }

    private fun activeEntryIds(): List<String> =
        manager.activeNotifications.filter { it.id == ENTRY_NOTIFICATION_ID }.mapNotNull { it.tag }

    private fun entryNotification(entry: NewEntry) = NotificationCompat.Builder(context, CHANNEL_NEW_ENTRIES)
        .setSmallIcon(R.drawable.ic_ares_glyph)
        .setContentTitle(entry.feedTitle)
        .setContentText(entry.title ?: context.getString(R.string.entry_untitled))
        .setStyle(NotificationCompat.BigTextStyle().bigText(entry.title ?: context.getString(R.string.entry_untitled)))
        .setGroup(GROUP_NEW_ENTRIES)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        .setAutoCancel(true)
        .setContentIntent(openEntryIntent(context, entry.id))
        .addAction(0, context.getString(R.string.action_mark_read), actionIntent(context, ACTION_MARK_READ, entry.id))
        .addAction(0, context.getString(R.string.action_bookmark), actionIntent(context, ACTION_BOOKMARK, entry.id))
        .build()

    private fun summaryNotification(newEntries: List<NewEntry>) = NotificationCompat.Builder(context, CHANNEL_NEW_ENTRIES).run {
        val feedCount = newEntries.map { it.feedId }.distinct().size
        val text = context.getString(
            R.string.notification_summary,
            context.resources.getQuantityString(R.plurals.notification_summary_entries, newEntries.size, newEntries.size),
            context.resources.getQuantityString(R.plurals.notification_summary_feeds, feedCount, feedCount),
        )
        val style = NotificationCompat.InboxStyle().setSummaryText(text)
        newEntries.take(SUMMARY_LINES).forEach { style.addLine("${it.feedTitle}: ${it.title ?: context.getString(R.string.entry_untitled)}") }
        setSmallIcon(R.drawable.ic_ares_glyph)
        setContentTitle(context.getString(R.string.notification_summary_title))
        setContentText(text)
        setStyle(style)
        setNumber(newEntries.size)
        setGroup(GROUP_NEW_ENTRIES)
        setGroupSummary(true)
        setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        setOnlyAlertOnce(true)
        setAutoCancel(true)
        setContentIntent(openAppIntent(context))
        build()
    }
}

fun notificationPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

fun notificationsAllowed(context: Context): Boolean =
    notificationPermissionGranted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun entryUri(entryId: String): Uri = Uri.Builder().scheme("ares").authority("entry").appendPath(entryId).build()

fun openEntryIntent(context: Context, entryId: String): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_ENTRY)
        .setData(entryUri(entryId))
        .putExtra(EXTRA_ENTRY_ID, entryId)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

fun actionIntent(context: Context, action: String, entryId: String): PendingIntent = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, NotificationActionReceiver::class.java)
        .setAction(action)
        .setData(entryUri(entryId))
        .putExtra(EXTRA_ENTRY_ID, entryId),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
