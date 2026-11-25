package com.m57.hermescontrol.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperationalNoticeIntentTest {
    @Test
    fun operationalNoticeDoesNotReuseChatActionToken() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val action = buildNotificationContentIntent(context, null)
        val notice = buildNotificationContentIntent(context, null, operational = true)
        val updatedNotice = buildNotificationContentIntent(context, null, operational = true)
        val reply = buildNotificationContentIntent(context, "session-1")
        try {
            assertNotEquals(action, notice)
            assertNotEquals(reply, notice)
            assertEquals(notice, updatedNotice)
        } finally {
            action.cancel()
            notice.cancel()
            reply.cancel()
        }
    }
}
