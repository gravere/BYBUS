package com.lody.virtual.server.am;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.stub.StubManifest;
import com.lody.virtual.helper.collection.SparseArray;
import com.lody.virtual.helper.utils.ComponentUtils;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VBinder;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.ServiceResult;
import com.lody.virtual.server.notification.VNotificationManagerService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Lody
 */
public class ActiveServices {

    private static final String TAG = "ActiveServices";

    private final VActivityManagerService mAms;
    private final Context mContext = VirtualCore.get().getContext();
    private final SparseArray<UserSpace> mUserSpaces = new SparseArray<>();
    private static class NotificationId {
        String tag;
        int id;

        public NotificationId(String tag, int id) {
            this.tag = tag;
            this.id = id;
        }
    }
    private final Map<ComponentName, NotificationId> mServiceIds = new HashMap<>();

    private class UserSpace {
        int userId;
        final Map<IBinder, BoundServiceInfo> mBoundServiceInfos = new HashMap<>();
        final Map<ComponentName, RunningServiceData> mRunningServices = new HashMap<>();

        public UserSpace(int userId) {
            this.userId = userId;
        }

        RunningServiceData getOrCreateRunningServiceInfo(ServiceInfo serviceInfo, ProcessRecord owner) {
            ComponentName component = ComponentUtils.toComponentName(serviceInfo);
            RunningServiceData data;
            synchronized (mRunningServices) {
                data = mRunningServices.get(component);
                if (data == null || data.owner != owner) {
                    data = new RunningServiceData(serviceInfo, owner);
                    Log.e(TAG, " getOrCreateRunningServiceInfo mRunningServices put" + component.toString());
                    mRunningServices.put(component, data);
                }
            }
            data.lastActivityTime = SystemClock.uptimeMillis();
            return data;
        }
    }

    private class RunningServiceData {
        ServiceInfo info;
        final ProcessRecord owner;
        int clientCount;
        int restartCount;
        int startId;
        final SparseArray<Intent> stickyIntents = new SparseArray<>();

        public long activeSince;
        public long lastActivityTime;

        public RunningServiceData(ServiceInfo info, ProcessRecord owner) {
            this.info = info;
            this.owner = owner;
            activeSince = SystemClock.elapsedRealtime();
        }
    }

    public ActiveServices(VActivityManagerService ams) {
        this.mAms = ams;
    }

    private class BoundServiceInfo implements IBinder.DeathRecipient {
        final UserSpace user;
        final IBinder connection;
        final RunningServiceData service;

        BoundServiceInfo(UserSpace user, IBinder connection, RunningServiceData service) {
            this.user = user;
            this.connection = connection;
            this.service = service;
        }

        @Override public void binderDied() {
            synchronized (user.mBoundServiceInfos) {
                if (user.mBoundServiceInfos.get(connection) == this) unbindService(user.userId, connection);
            }
        }
    }

    private UserSpace getUserSpace(int userId) {
        UserSpace userSpace;
        synchronized (mUserSpaces) {
            userSpace = mUserSpaces.get(userId);
            if (userSpace == null) {
                userSpace = new UserSpace(userId);
                mUserSpaces.put(userId, userSpace);
            }
        }
        return userSpace;
    }


    public void onStartCommand(int userId, int startId, ServiceInfo serviceInfo, Intent intent) {
        UserSpace userSpace = getUserSpace(userId);
        int uid = VUserHandle.getUid(userId, VUserHandle.getAppId(serviceInfo.applicationInfo.uid));
        ProcessRecord owner = mAms.findProcess(ComponentUtils.getProcessName(serviceInfo), uid);
        if (owner != null && owner.pid == VBinder.getCallingPid()) {
            synchronized (userSpace.mRunningServices) {
                RunningServiceData info = userSpace.getOrCreateRunningServiceInfo(serviceInfo, owner);
                info.stickyIntents.put(startId, intent);
            }
        }
    }

    public void onDestroy(int userId, ComponentName componentName) {
        UserSpace userSpace = getUserSpace(userId);
        synchronized (userSpace.mRunningServices) {
            RunningServiceData info = userSpace.mRunningServices.get(componentName);
            if (info != null && info.owner.pid == VBinder.getCallingPid()) {
                info.stickyIntents.clear();
                info.startId = 0;
            }
        }
    }

    public ComponentName startService(int userId, Intent intent) {
        UserSpace userSpace = getUserSpace(userId);
        ServiceInfo serviceInfo = VirtualCore.get().resolveServiceInfo(intent, userId);
        if (serviceInfo == null) {
            return null;
        }
        ComponentName component = ComponentUtils.toComponentName(serviceInfo);
        ProcessRecord targetApp = mAms.startProcessIfNeedLocked(
                ComponentUtils.getProcessName(serviceInfo),
                userId,
                serviceInfo.packageName,
                -1,
                VBinder.getCallingUid(),
                VActivityManager.PROCESS_TYPE_SERVICE
        );
        if (targetApp == null) {
            return null;
        }
        final Intent proxyIntent = new Intent();
        proxyIntent.setAction(System.currentTimeMillis() + "");
        proxyIntent.setClassName(StubManifest.getStubPackageName(targetApp.is64bit), StubManifest.getStubServiceName(targetApp.vpid));
        RunningServiceData info = userSpace.getOrCreateRunningServiceInfo(serviceInfo, targetApp);
        int previousStartId;
        int startId;
        synchronized (userSpace.mRunningServices) {
            previousStartId = info.startId;
            startId = ++info.startId;
        }
        proxyIntent.putExtra("_VA_|_start_id_", startId);
        proxyIntent.putExtra("_VA_|_service_info_", serviceInfo);
        proxyIntent.putExtra("_VA_|_intent_", intent);
        proxyIntent.putExtra("_VA_|_user_id_", userId);
        // Context.startService is a Binder call, not a UI operation. Return its outcome
        // to the caller instead of reporting success and later crashing the server's
        // main thread when Android rejects a background start.
        if (!startProxyService(mContext, proxyIntent)) {
            synchronized (userSpace.mRunningServices) {
                if (info.startId == startId) info.startId = previousStartId;
                if (info.startId == 0 && info.clientCount == 0 && info.stickyIntents.size() == 0
                        && userSpace.mRunningServices.get(component) == info) {
                    userSpace.mRunningServices.remove(component);
                }
            }
            return null;
        }
        return component;
    }

    static boolean startProxyService(Context context, Intent intent) {
        try {
            return context.startService(intent) != null;
        } catch (IllegalStateException | SecurityException denied) {
            VLog.w("LimbusVA", "Virtual service start rejected type=%s", denied.getClass().getSimpleName());
            return false;
        }
    }

    public Intent bindService(int userId, Intent intent, ServiceInfo serviceInfo, final IBinder conn, int flags) {
        final UserSpace userSpace = getUserSpace(userId);
        ProcessRecord targetApp = mAms.startProcessIfNeedLocked(
                ComponentUtils.getProcessName(serviceInfo),
                userId,
                serviceInfo.packageName,
                -1,
                VBinder.getCallingUid(),
                VActivityManager.PROCESS_TYPE_SERVICE_BIND
        );
        if (targetApp == null) {
            return null;
        }
        RunningServiceData data = userSpace.getOrCreateRunningServiceInfo(serviceInfo, targetApp);
        synchronized (userSpace.mBoundServiceInfos) {
            BoundServiceInfo previous = userSpace.mBoundServiceInfos.get(conn);
            if (previous == null || previous.service != data) {
                BoundServiceInfo binding = new BoundServiceInfo(userSpace, conn, data);
                try {
                    conn.linkToDeath(binding, 0);
                } catch (RemoteException disconnected) {
                    return null;
                }
                if (previous != null) unbindService(userId, conn);
                userSpace.mBoundServiceInfos.put(conn, binding);
                synchronized (userSpace.mRunningServices) {
                    data.clientCount++;
                }
            }
        }
        Intent proxyIntent = new Intent();
        proxyIntent.setAction(System.currentTimeMillis() + "");
        proxyIntent.setClassName(StubManifest.getStubPackageName(targetApp.is64bit), StubManifest.getStubServiceName(targetApp.vpid));
        int startId;
        synchronized (userSpace.mRunningServices) {
            startId = data.startId++;//bindService need
        }
        proxyIntent.putExtra("_VA_|_start_id_", startId);
        proxyIntent.putExtra("_VA_|_service_info_", serviceInfo);
        proxyIntent.putExtra("_VA_|_intent_", intent);
        proxyIntent.putExtra("_VA_|_user_id_", userId);
        return proxyIntent;
    }


    public ServiceResult onUnbind(int userId, ComponentName component) {
        UserSpace userSpace = getUserSpace(userId);
        ServiceResult serviceResult = new ServiceResult();
        synchronized (userSpace.mRunningServices) {
            RunningServiceData info = userSpace.mRunningServices.get(component);
            if (info == null || info.owner.pid != VBinder.getCallingPid()) {
                Log.e(TAG, " onUnbind RunningServiceData is null " + component.toString());
                serviceResult.died = true;
                return serviceResult;
            }
            serviceResult.startId = info.startId;
            serviceResult.clientCount = info.clientCount;
            serviceResult.restart = info.clientCount <= 0 && info.startId > 0;
        }
        return serviceResult;
    }

    //xdja
    public void stopServiceByPkg(int userId, String pkgName){
        UserSpace userSpace = getUserSpace(userId);
        RunningServiceData data;
        synchronized (userSpace.mRunningServices) {

            Set<Map.Entry<ComponentName, RunningServiceData>> set = userSpace.mRunningServices.entrySet();
            Iterator<Map.Entry<ComponentName, RunningServiceData>> iterator = set.iterator();

            while (iterator.hasNext()){
                Map.Entry<ComponentName, RunningServiceData> entry = iterator.next();
                ComponentName a = entry.getKey();
                if(pkgName.equals(a.getPackageName())){
                    data = userSpace.mRunningServices.get(a);
                    stopService(userId, a, -1);
                    try {
                        Log.e("wxd", " stop " + a);
                        VActivityManager.get().stopService(userId, data.info);
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }
                iterator.remove();
            }
        }
    }

    public int stopService(int userId, ComponentName component, int targetStartId) {
        UserSpace userSpace = getUserSpace(userId);
        RunningServiceData data;
        synchronized (userSpace.mRunningServices) {
            data = userSpace.mRunningServices.get(component);
        }
        if (data == null) {
            return 0;
        }
        int startId = data.startId;
        if (targetStartId == -1) {
            targetStartId = startId;
        }
        synchronized (userSpace.mRunningServices) {
            data.stickyIntents.remove(targetStartId);
        }
        if (targetStartId != startId) {
            VLog.e(TAG, "stopService prevented because not last startId: " + startId + " / " + targetStartId);
            return -1;
        }
        synchronized (userSpace.mRunningServices) {
            if (data.clientCount > 0) {
                VLog.d(TAG, "stopService prevented because has connection: " + component);
                return -1;
            }
            data.startId = 0;
            userSpace.mRunningServices.remove(component);
        }
        return startId;
    }


    public void unbindService(int userId, IBinder binder) {
        UserSpace userSpace = getUserSpace(userId);
        synchronized (userSpace.mBoundServiceInfos) {
            BoundServiceInfo boundServiceInfo = userSpace.mBoundServiceInfos.remove(binder);
            if (boundServiceInfo != null) {
                binder.unlinkToDeath(boundServiceInfo, 0);
                synchronized (userSpace.mRunningServices) {
                    boundServiceInfo.service.clientCount--;
                }
            }
        }
    }

    public void processDied(ProcessRecord process) {
        UserSpace userSpace;
        synchronized (mUserSpaces) {
            userSpace = mUserSpaces.get(process.userId);
        }
        if (userSpace == null) {
            return;
        }
        synchronized (userSpace.mRunningServices) {
            Iterator<RunningServiceData> it = userSpace.mRunningServices.values().iterator();
            while (it.hasNext()) {
                RunningServiceData data = it.next();
                if (data.owner == process) {
                    Log.e(TAG, " processDied remove runningServices " + data.info.toString());
                    it.remove();
                }
            }
        }
    }

    public List<ActivityManager.RunningServiceInfo> getServices(int userId) {
        UserSpace userSpace;
        synchronized (mUserSpaces) {
            userSpace = mUserSpaces.get(userId);
        }
        if (userSpace == null) {
            return Collections.emptyList();
        }
        synchronized (userSpace.mRunningServices) {
            List<ActivityManager.RunningServiceInfo> infos = new ArrayList<>(userSpace.mRunningServices.size());
            for (RunningServiceData data : userSpace.mRunningServices.values()) {
                int appId = VUserHandle.getAppId(data.info.applicationInfo.uid);
                int uid = VUserHandle.getUid(userId, appId);
                ProcessRecord r = data.owner;
                ActivityManager.RunningServiceInfo info = new ActivityManager.RunningServiceInfo();
                info.process = r.processName;
                info.pid = r.pid;
                info.uid = uid;
                info.clientCount = data.clientCount;
                info.clientPackage = data.info.packageName;
                info.service = ComponentUtils.toComponentName(data.info);
                info.started = true;
                info.activeSince = data.activeSince;
                info.lastActivityTime = data.lastActivityTime;
                infos.add(info);
            }
            return infos;
        }
    }

    public void setServiceForeground(ComponentName component, int userId, int id, String tag, boolean cancel) {
        NotificationId notificationId;
        synchronized (mServiceIds) {
            notificationId = mServiceIds.get(component);
        }
        if (cancel) {
            if (notificationId == null) {
                return;
            }
            id = notificationId.id;
            tag = notificationId.tag;
            VLog.d("kk", "setServiceForeground cancel %s %d[%s]", component, id, tag);
            try {
                VNotificationManagerService.get().cancelNotification(component.getPackageName(), tag, id, userId);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        } else {
            VLog.d("kk", "setServiceForeground add %s %d[%s]", component, id, tag);
            synchronized (mServiceIds) {
                mServiceIds.put(component, new NotificationId(tag, id));
            }
        }
    }
}
