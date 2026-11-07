package com.lody.virtual.client.stub;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.VLog;


/**
 * @author Lody
 */
public class KeepAliveService extends Service {
    private boolean foregroundRejected;

    /** One request from a user-initiated launch; never schedule background retry loops. */
    public static void requestStart(Context context) {
        try {
            Intent intent = new Intent(context, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException error) {
            if (!isForegroundStartRejected(error)) throw error;
            VLog.w("LimbusVA", "KeepAliveService start deferred by background policy");
        }
    }

    private static boolean isForegroundStartRejected(RuntimeException error) {
        // Avoid resolving an API 31 exception class when loading the service on older Android.
        return "android.app.ForegroundServiceStartNotAllowedException".equals(error.getClass().getName());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            if (useDirectForegroundPromotion(Build.VERSION.SDK_INT)) {
                // The legacy two-service notification-hiding trick does not promote this service on
                // modern Android. It leaves the VirtualApp server at a killable cached-service adj and
                // a background restart cannot legally start the second foreground service.
                HiddenForeNotification.bindForeground(this);
                VLog.i("LimbusVA", "KeepAliveService promoted directly on Android "
                        + Build.VERSION.SDK_INT);
            } else if (VirtualCore.getConfig().isHideForegroundNotification()) {
                Intent intent = new Intent(this, HiddenForeNotification.class);
                startService(intent);
            } else {
                HiddenForeNotification.bindForeground(this);
            }
        } catch (RuntimeException error) {
            if (!isForegroundStartRejected(error)) throw error;
            foregroundRejected = true;
            // Cancel the foreground-service deadline as well as sticky restart. Keep the server alive.
            stopSelf();
            VLog.w("LimbusVA", "KeepAliveService promotion rejected; stopped optional service without exiting server");
        }
    }

    public static boolean useDirectForegroundPromotion(int sdkInt) {
        return sdkInt >= Build.VERSION_CODES.O;
    }

    @Override
    public void onDestroy() {
        if (foregroundRejected) {
            super.onDestroy();
            return;
        }
        if (useDirectForegroundPromotion(Build.VERSION.SDK_INT)) {
            HiddenForeNotification.hideForeground(this);
        } else if (VirtualCore.getConfig().isHideForegroundNotification()) {
            stopService(new Intent(this, HiddenForeNotification.class));
        } else {
            HiddenForeNotification.hideForeground(this);
        }
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (foregroundRejected) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }
}
