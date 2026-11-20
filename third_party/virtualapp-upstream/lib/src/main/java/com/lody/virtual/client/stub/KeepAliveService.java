package com.lody.virtual.client.stub;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.server.am.VActivityManagerService;


/**
 * @author Lody
 */
public class KeepAliveService extends Service {
    private boolean foregroundRejected;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long launchDeadline;
    private boolean guestSeen;
    private static final String LAUNCH = "com.lody.virtual.action.USER_GUEST_LAUNCH";
    private final Runnable checkGuest = new Runnable() {
        @Override public void run() {
            String guest = VirtualCore.getConfig().getForegroundServiceGuestPackage();
            if (guest == null || foregroundRejected) return;
            boolean running = VActivityManagerService.get().getRunningAppProcesses(guest, 0)
                    .stream().anyMatch(process -> process.pid > 0);
            guestSeen |= running;
            if (!running && (guestSeen || SystemClock.elapsedRealtime() >= launchDeadline)) {
                // Stop only this optional service, never the server, Google components or saved data.
                HiddenForeNotification.hideForeground(KeepAliveService.this);
                stopSelf();
                return;
            }
            main.postDelayed(this, 3000);
        }
    };

    /** One request from a user-initiated launch; never schedule background retry loops. */
    public static void requestStart(Context context) {
        try {
            Intent intent = new Intent(context, KeepAliveService.class).setAction(LAUNCH);
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
        main.removeCallbacks(checkGuest);
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
        if (VirtualCore.getConfig().getForegroundServiceGuestPackage() != null) {
            if (intent != null && LAUNCH.equals(intent.getAction())) {
                launchDeadline = SystemClock.elapsedRealtime() + 60_000;
                guestSeen = false;
            }
            main.removeCallbacks(checkGuest);
            checkGuest.run();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }
}
