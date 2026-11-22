package com.example.limbuszhcn

import android.content.Intent
import android.os.Binder
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.helper.compat.NewIntentCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewIntentDeliveryTest {
    @Test fun resumedActivityConsumesCallbackDuringResume() {
        ActivityScenario.launch(NewIntentProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.events.clear()
                assertTrue(NewIntentCompat.deliver(mirror.android.app.ActivityThread.currentActivityThread.call(),
                    mirror.android.app.Activity.mToken.get(activity),
                    Intent(activity.intent).setAction(NewIntentProbeActivity.CALLBACK), "android"))
                assertEquals(listOf("pause", "intent", "resume"), activity.events)
                assertEquals(1, activity.consumed)
            }
        }
    }

    @Test fun pausedActivityReceivesResultBeforeResume() {
        ActivityScenario.launch(NewIntentProbeActivity::class.java).use { scenario ->
            lateinit var target: NewIntentProbeActivity
            scenario.onActivity { target = it }
            scenario.moveToState(Lifecycle.State.STARTED)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                target.events.clear()
                assertTrue(NewIntentCompat.deliver(mirror.android.app.ActivityThread.currentActivityThread.call(),
                    mirror.android.app.Activity.mToken.get(target),
                    Intent(target.intent).setAction(NewIntentProbeActivity.CALLBACK), "android"))
                assertEquals(listOf("intent", "resume"), target.events)
                assertEquals(1, target.consumed)
            }
        }
    }

    @Test fun staleTokenIsNotReportedAsDelivered() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertFalse(NewIntentCompat.deliver(mirror.android.app.ActivityThread.currentActivityThread.call(), Binder(),
                Intent(NewIntentProbeActivity.CALLBACK), "android"))
        }
    }
}
