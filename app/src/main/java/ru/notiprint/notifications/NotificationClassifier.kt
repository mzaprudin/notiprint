package ru.notiprint.notifications

import android.app.Notification
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import android.service.notification.StatusBarNotification
import java.util.Locale
import ru.notiprint.data.NotificationKind
import ru.notiprint.settings.AppSettings

object NotificationClassifier {
    private val missedCallPackages = setOf(
        "com.android.contacts",
        "com.android.server.telecom",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.coloros.dialer",
        "com.coloros.phone",
        "com.oplus.dialer",
        "com.oplus.phone",
        "com.huawei.contacts",
    )
    private val missedCallChannels = setOf(
        "NC_ID_MISSEDCALL",
        "TelecomMissedCalls",
        "notification_channel_missed_calls",
        "missed_calls",
        "missed_call",
        "missed_call_notification",
        "phone_missed_call",
    )
    private val knownSmsPackages = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
    )
    private val knownCalendarPackages = setOf(
        "com.google.android.calendar",
        "com.samsung.android.calendar",
        "com.android.calendar",
    )

    fun classify(context: Context, notification: StatusBarNotification, settings: AppSettings): NotificationKind? {
        val category = notification.notification.category
        val packageName = notification.packageName

        return when {
            settings.missedCallsEnabled && isMissedCallNotification(notification) -> {
                NotificationKind.MISSED_CALL
            }

            category == Notification.CATEGORY_EVENT && settings.calendarEnabled -> {
                NotificationKind.CALENDAR
            }

            category == Notification.CATEGORY_MESSAGE &&
                packageName in smsPackages(context) && settings.smsEnabled && !canReceiveSmsDirectly(context) -> {
                NotificationKind.SMS
            }

            packageName in smsPackages(context) &&
                notification.notification.extras.containsKey(Notification.EXTRA_MESSAGES) &&
                settings.smsEnabled && !canReceiveSmsDirectly(context) -> {
                NotificationKind.SMS
            }

            else -> null
        }
    }

    private fun smsPackages(context: Context): Set<String> {
        val defaultPackage = Telephony.Sms.getDefaultSmsPackage(context)
        return if (defaultPackage == null) knownSmsPackages else knownSmsPackages + defaultPackage
    }

    private fun canReceiveSmsDirectly(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun isMissedCallNotification(notification: StatusBarNotification): Boolean =
        isMissedCallNotification(
            packageName = notification.packageName,
            tag = notification.tag,
            channelId = notification.notification.channelId,
            category = notification.notification.category,
            title = notification.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            message = notification.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
        )

    /**
     * Realme/OPPO diallers do not all set CATEGORY_MISSED_CALL or use the same
     * channel name. Keep the fallback narrow: it only accepts phone/dialler
     * packages and requires either a missed-call channel/tag or missed-call
     * wording in the notification content.
     */
    internal fun isMissedCallNotification(
        packageName: String,
        tag: String?,
        channelId: String?,
        category: String?,
        title: String,
        message: String,
    ): Boolean {
        if (category == Notification.CATEGORY_MISSED_CALL) return true

        val normalizedPackage = packageName.lowercase(Locale.ROOT)
        val phonePackage = normalizedPackage in missedCallPackages ||
            PHONE_PACKAGE_MARKERS.any(normalizedPackage::contains)
        if (!phonePackage) return false

        val normalizedTag = tag?.lowercase(Locale.ROOT).orEmpty()
        val normalizedChannel = channelId?.lowercase(Locale.ROOT).orEmpty()
        val knownMissedCallMetadata = normalizedTag == "missedcallnotifier" ||
            normalizedTag.contains("missed") ||
            normalizedChannel in missedCallChannels.map { it.lowercase(Locale.ROOT) }.toSet() ||
            normalizedChannel.contains("missed")
        if (knownMissedCallMetadata) return true

        val content = "$title $message".lowercase(Locale.ROOT)
        return MISSED_CALL_TEXT_MARKERS.any(content::contains)
    }

    private val PHONE_PACKAGE_MARKERS = setOf("dialer", "telecom", "incallui", "phone")
    private val MISSED_CALL_TEXT_MARKERS = setOf(
        "пропущ",
        "неотвечен",
        "не отвечен",
        "missed call",
        "missed calls",
        "unanswered call",
    )
}
