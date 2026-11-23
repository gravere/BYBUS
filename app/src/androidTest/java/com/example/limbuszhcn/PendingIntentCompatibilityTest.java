package com.example.limbuszhcn;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.test.platform.app.InstrumentationRegistry;
import com.lody.virtual.helper.compat.PendingIntentCreatorCompat;
import com.lody.virtual.helper.utils.ComponentUtils;
import com.lody.virtual.remote.IntentSenderData;
import com.lody.virtual.remote.IntentSenderExtData;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class PendingIntentCompatibilityTest {
    @Test public void immutableRequestDoesNotAcceptServerSideFillIn() {
        Intent original = new Intent("original").putExtra("request", "original");
        IntentSenderData data = new IntentSenderData("com.google.android.gms", new Binder(),
                original, PendingIntent.FLAG_IMMUTABLE | Intent.FILL_IN_ACTION, 2, 0);
        IntentSenderExtData ext = new IntentSenderExtData(data.token,
                new Intent("replacement").putExtra("request", "replacement"), null, null,
                1, null, Intent.FLAG_ACTIVITY_CLEAR_TOP, Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent target = new Intent(original);
        data.applyFillIn(target, ext);
        assertTrue(original.filterEquals(target));
        assertEquals("original", target.getStringExtra("request"));
        assertEquals(0, target.getFlags());
        data.flags = Intent.FILL_IN_ACTION;
        data.applyFillIn(target, ext);
        assertEquals("replacement", target.getAction());
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP, target.getFlags());
    }
    @Before public void allowFrameworkReflection() throws Exception {
        Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass")
                .getMethod("addHiddenApiExemptions", String[].class)
                .invoke(null, (Object) new String[]{"Landroid/"});
    }

    @Test public void distinctConsentRequestsCannotReplaceEachOther() {
        if (Build.VERSION.SDK_INT < 29) return;
        String creator = "com.google.android.gms";
        Intent first = new Intent().setComponent(new ComponentName(creator,
                "org.microg.gms.auth.AskPermissionActivity")).setIdentifier("consent-one");
        Intent second = new Intent(first).setIdentifier("consent-two");
        Context host = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent a = ComponentUtils.redirectIntentSender(2, creator, first);
        Intent b = ComponentUtils.redirectIntentSender(2, creator, second);
        assertFalse(a.filterEquals(b));
        assertTrue(a.filterEquals(ComponentUtils.redirectIntentSender(2, creator, new Intent(first))));
        PendingIntent one = PendingIntent.getActivity(host, 75318, a,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent two = PendingIntent.getActivity(host, 75318, b,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            assertNotEquals(one, two);
            assertEquals(one, PendingIntent.getActivity(host, 75318, a,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE));
            if (Build.VERSION.SDK_INT >= 31) assertTrue(one.isImmutable());
            assertFalse(a.filterEquals(ComponentUtils.redirectIntentSender(2, creator,
                    new Intent(first).setClassName(creator, "org.microg.gms.auth.AskPackageOverrideActivity"))));
            assertFalse(a.filterEquals(ComponentUtils.redirectIntentSender(2, creator,
                    new Intent(first).setPackage(creator))));
            assertFalse(a.filterEquals(ComponentUtils.redirectIntentSender(2, creator,
                    new Intent(first).setType("text/plain"))));
        } finally { one.cancel(); two.cancel(); }
    }

    @Test public void creatorInfoKeepsFrameworkFlagsAndRejectsUnrelatedRecords() throws Exception {
        if (Build.VERSION.SDK_INT < 31) return;
        Context host = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Class<?> type = Class.forName("android.app.ActivityManager$PendingIntentInfo");
        int uid = host.getApplicationInfo().uid;
        Object real = type.getConstructor(String.class, int.class, boolean.class, int.class)
                .newInstance(host.getPackageName(), uid, true, 2);
        IBinder token = new Binder();
        IntentSenderData record = new IntentSenderData("com.google.android.gms", token,
                new Intent(), 0, 2, 0);
        Object restored = PendingIntentCreatorCompat.restoreInfo(real, host.getPackageName(), uid,
                record, token, 0, 10001);
        assertEquals(record.creator, type.getMethod("getCreatorPackage").invoke(restored));
        assertEquals(10001, type.getMethod("getCreatorUid").invoke(restored));
        assertEquals(true, type.getMethod("isImmutable").invoke(restored));
        assertEquals(2, type.getMethod("getIntentSenderType").invoke(restored));
        assertSame(real, PendingIntentCreatorCompat.restoreInfo(real, host.getPackageName(), uid,
                record, new Binder(), 0, 10001));
        assertSame(real, PendingIntentCreatorCompat.restoreInfo(real, host.getPackageName(), uid,
                record, token, 10, 10001));
        assertSame(real, PendingIntentCreatorCompat.restoreInfo(real, host.getPackageName(), uid + 1,
                record, token, 0, 10001));
        assertSame(real, PendingIntentCreatorCompat.restoreInfo(real, "other.package", uid,
                record, token, 0, 10001));
        assertSame(real, PendingIntentCreatorCompat.restoreInfo(real, host.getPackageName(), uid,
                null, token, 0, 10001));
        record.creator = "other.guest";
        restored = PendingIntentCreatorCompat.restoreInfo(real, host.getPackageName(), uid,
                record, token, 0, 10002);
        assertEquals("other.guest", type.getMethod("getCreatorPackage").invoke(restored));
    }
}
