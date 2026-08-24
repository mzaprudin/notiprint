package ru.notiprint.notifications

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationClassifierTest {
    @Test
    fun `accepts Realme dialer wording without standard category`() {
        assertTrue(
            NotificationClassifier.isMissedCallNotification(
                packageName = "com.oplus.dialer",
                tag = null,
                channelId = "phone_notifications",
                category = null,
                title = "Пропущенный вызов",
                message = "Иван",
            ),
        )
    }

    @Test
    fun `accepts standard missed call category from any dialer package`() {
        assertTrue(
            NotificationClassifier.isMissedCallNotification(
                packageName = "com.realme.unknownphone",
                tag = null,
                channelId = null,
                category = Notification.CATEGORY_MISSED_CALL,
                title = "Call",
                message = "",
            ),
        )
    }

    @Test
    fun `does not classify an ordinary phone notification as missed call`() {
        assertFalse(
            NotificationClassifier.isMissedCallNotification(
                packageName = "com.oplus.dialer",
                tag = null,
                channelId = "phone_notifications",
                category = null,
                title = "Входящий вызов",
                message = "Иван",
            ),
        )
    }

    @Test
    fun `does not classify unrelated app with missed wording`() {
        assertFalse(
            NotificationClassifier.isMissedCallNotification(
                packageName = "com.example.messaging",
                tag = null,
                channelId = null,
                category = null,
                title = "Пропущенный вызов",
                message = "",
            ),
        )
    }
}
