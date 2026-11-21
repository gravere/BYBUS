package com.example.limbuszhcn

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.limbuszhcn.selfupdate.AppUpdateController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyUpdateQuotaTest {
    @Test fun oldRestCountdownDoesNotPreventOpeningOrCheckingAfterUpgrade() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val preferences = app.getSharedPreferences("bybus-app-update", 0)
        val now = System.currentTimeMillis()
        preferences.edit().putInt("retrySchema", 2).putLong("rateRecordedAt", now)
            .putLong("rateRetryAt", now + 3_600_000).putLong("retryAt", now + 3_600_000).commit()
        val controller = AppUpdateController(app)
        try {
            assertNull(controller.state.retryAtElapsed)
            assertTrue(controller.nextNoticeDelay() <= 1_000)
            assertFalse(preferences.contains("retryAt"))
            assertFalse(preferences.contains("rateRetryAt"))
            assertFalse(preferences.contains("rateRecordedAt"))
        } finally { controller.close() }
    }
}
