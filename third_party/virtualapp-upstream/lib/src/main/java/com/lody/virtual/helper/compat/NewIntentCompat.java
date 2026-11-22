package com.lody.virtual.helper.compat;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import mirror.android.app.ActivityThread;
import mirror.android.app.ActivityThreadNMR1;
import mirror.com.android.internal.content.ReferrerIntent;

/** Deliver through Android's transaction executor, including pause/new-intent/resume ordering. */
public final class NewIntentCompat {
    private NewIntentCompat() { }

    public static boolean deliver(Object thread, IBinder token, Intent original, String creator) {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("New intents must be delivered on the activity thread");
        Map<IBinder, Object> activities = ActivityThread.mActivities.get(thread);
        Object record = activities == null ? null : activities.get(token);
        Activity activity = record == null ? null : ActivityThread.ActivityClientRecord.activity.get(record);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.w("LimbusVA", "New intent target is no longer active");
            ContainerRuntimeEvent.NEW_INTENT_FAILED.record(1);
            return false;
        }
        try {
            Intent intent = Build.VERSION.SDK_INT >= 22
                    ? ReferrerIntent.ctor.newInstance(original, creator) : original;
            if (intent == null) throw new IllegalStateException("Referrer intent unavailable");
            List<Intent> intents = Collections.singletonList(intent);
            if (Build.VERSION.SDK_INT >= 28) {
                // handleNewIntent changed from IBinder to ActivityClientRecord on Android 12.
                // Calling it alone also skips the lifecycle transitions required by Firebase.
                // Use the same local transaction path as ActivityThread on every modern release.
                TransactionApi api = TransactionApi.INSTANCE;
                Object item = api.newIntent(token, intents);
                Object transaction = api.newTransaction(token);
                api.add.invoke(transaction, item);
                api.execute.invoke(thread, transaction);
            } else if (ActivityThreadNMR1.performNewIntents != null) {
                ActivityThreadNMR1.performNewIntents.callWithException(thread, token, intents, true);
            } else if (ActivityThread.performNewIntents != null) {
                ActivityThread.performNewIntents.callWithException(thread, token, intents);
            } else throw new NoSuchMethodException("New intent dispatch unavailable");
            Log.i("LimbusVA", "New intent delivered activity=" + activity.getClass().getName());
            ContainerRuntimeEvent.NEW_INTENT_DELIVERED.record(Build.VERSION.SDK_INT);
            return true;
        } catch (Throwable error) {
            // Never log an OAuth URI, payload, exception message or reflected arguments.
            Log.e("LimbusVA", "New intent delivery failed sdk=" + Build.VERSION.SDK_INT
                    + " cause=" + error.getClass().getSimpleName());
            ContainerRuntimeEvent.NEW_INTENT_FAILED.record(2);
            return false;
        }
    }

    private static final class TransactionApi {
        static final TransactionApi INSTANCE = new TransactionApi();
        final Class<?> transactionClass, itemClass, appThreadClass;
        final Method add, execute;
        final boolean tokenOnItem;

        TransactionApi() {
            try {
                transactionClass = Class.forName("android.app.servertransaction.ClientTransaction");
                itemClass = Class.forName("android.app.servertransaction.NewIntentItem");
                appThreadClass = Class.forName("android.app.IApplicationThread");
                Class<?> baseItem = Class.forName("android.app.servertransaction.ClientTransactionItem");
                Method addItem;
                try { addItem = transactionClass.getMethod("addTransactionItem", baseItem); }
                catch (NoSuchMethodException legacy) { addItem = transactionClass.getMethod("addCallback", baseItem); }
                add = addItem;
                tokenOnItem = "addTransactionItem".equals(add.getName());
                execute = Class.forName("android.app.ClientTransactionHandler")
                        .getMethod("executeTransaction", transactionClass);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }

        Object newIntent(IBinder token, List<Intent> intents) throws ReflectiveOperationException {
            Class<?>[] signature = tokenOnItem
                    ? new Class<?>[]{IBinder.class, List.class, boolean.class}
                    : new Class<?>[]{List.class, boolean.class};
            Object[] args = tokenOnItem ? new Object[]{token, intents, true} : new Object[]{intents, true};
            // Android 16 removed the object-pool factories in favor of exact constructors.
            try { return itemClass.getMethod("obtain", signature).invoke(null, args); }
            catch (NoSuchMethodException noPool) {
                Constructor<?> constructor = itemClass.getDeclaredConstructor(signature);
                constructor.setAccessible(true);
                return constructor.newInstance(args);
            }
        }

        Object newTransaction(IBinder token) throws ReflectiveOperationException {
            try {
                return tokenOnItem
                        ? transactionClass.getMethod("obtain", appThreadClass).invoke(null, new Object[]{null})
                        : transactionClass.getMethod("obtain", appThreadClass, IBinder.class).invoke(null, null, token);
            } catch (NoSuchMethodException noPool) {
                Constructor<?> constructor = transactionClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            }
        }
    }
}
