package com.lody.virtual.client.hook.proxies.pm;

import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.IInterface;
import android.os.Build;
import android.util.Log;

import com.lody.virtual.client.VClient;
import com.lody.virtual.client.env.VirtualRuntime;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.core.InvocationStubManager;
import com.lody.virtual.client.hook.annotations.Inject;
import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodInvocationProxy;
import com.lody.virtual.client.hook.base.MethodInvocationStub;
import com.lody.virtual.client.hook.base.ReplaceCallingPkgMethodProxy;
import com.lody.virtual.client.hook.base.ResultStaticMethodProxy;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.utils.Reflect;
import com.lody.virtual.helper.compat.LimbusSafetyAlert;
import com.lody.virtual.helper.compat.PackageQueryTrace;
import com.lody.virtual.helper.compat.PackageManagerUserScope;
import com.lody.virtual.helper.compat.RuntimeInterfaceAdapter;
import com.lody.virtual.helper.compat.ParceledListSliceCompat;
import com.lody.virtual.client.hook.utils.MethodParameterUtils;
import com.lody.virtual.os.VUserHandle;
import java.lang.reflect.Method;

import mirror.android.app.ActivityThread;

/**
 * @author Lody
 */
@Inject(MethodProxies.class)
public final class PackageManagerStub extends MethodInvocationProxy<MethodInvocationStub<IInterface>> {
    private static final String GAME = "com.ProjectMoon.LimbusCompany";
    private IInterface mConcreteInterface;
    private Class<IInterface> mRuntimeContract;

    /** Run before the game's Application is created, never in the host or Google processes. */
    @SuppressWarnings("unchecked")
    public static void prepareGameInterface(String packageName) {
        if (!GAME.equals(packageName) || Build.VERSION.SDK_INT < 26) return;
        PackageManagerStub stub = InvocationStubManager.getInstance().findInjector(PackageManagerStub.class);
        if (stub == null || stub.mConcreteInterface != null) return;
        try {
            Class<IInterface> contract = (Class<IInterface>) Class.forName("android.content.pm.IPackageManager");
            IInterface adapted = RuntimeInterfaceAdapter.wrap(contract, stub.getInvocationStub().getProxyInterface());
            stub.mRuntimeContract = contract;
            stub.mConcreteInterface = adapted;
            stub.inject();
            Log.i("LimbusVA", "Package interface ready sdk=" + Build.VERSION.SDK_INT
                    + " methods=" + contract.getMethods().length);
        } catch (Throwable error) {
            stub.mConcreteInterface = null;
            stub.mRuntimeContract = null;
            // A generation failure must not abort binding or expose the unvirtualized system PM.
            Log.w("LimbusVA", "Package interface retained existing dispatch: " + error.getClass().getName());
        }
    }
    private static final PackageQueryTrace TRACE = new PackageQueryTrace();
    private static final java.util.concurrent.atomic.AtomicInteger SOURCE_QUERY_REPORTS = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger COMPONENT_QUERY_REPORTS = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger LATE_ERRORS = new java.util.concurrent.atomic.AtomicInteger();

    private static Object beginQuery(Method method, boolean proxied) {
        ApplicationInfo current = VClient.get().getCurrentApplicationInfo();
        if (current == null || !GAME.equals(current.packageName)) return null;
        PackageQueryTrace.PendingQuery query = TRACE.begin(method.getName(), proxied, android.os.SystemClock.elapsedRealtime());
        if (query != null) VirtualCore.getConfig().onLimbusPackageQueries(query.beginning());
        return query;
    }

    private static void endQuery(Object observation, Method method, Object result, Throwable error) {
        if (!(observation instanceof PackageQueryTrace.PendingQuery)) {
            ApplicationInfo current = VClient.get().getCurrentApplicationInfo();
            if (error != null && current != null && GAME.equals(current.packageName) &&
                    LATE_ERRORS.getAndIncrement() < 8) {
                VirtualCore.getConfig().onLimbusPackageQueries("late_query_error method=" + method.getName() +
                        " kind=" + (error instanceof SecurityException ? "security" :
                        error instanceof ClassCastException ? "type" : "exception"));
            }
            return;
        }
        PackageQueryTrace.PendingQuery query = (PackageQueryTrace.PendingQuery) observation;
        String summary = query.finished(result, error, android.os.SystemClock.elapsedRealtime());
        if (error == null && result != null && !method.getReturnType().isPrimitive()
                && !method.getReturnType().isInstance(result)) summary += " declaredTypeMismatch=true";
        ApplicationInfo app = result instanceof ApplicationInfo ? (ApplicationInfo) result :
                result instanceof PackageInfo ? ((PackageInfo) result).applicationInfo : null;
        if (app != null && GAME.equals(app.packageName)) {
            // No arbitrary package names, paths, account data or recursive PM queries.
            summary += " role=game flags=0x" + Integer.toHexString(app.flags) +
                    " enabled=" + app.enabled + " targetSdk=" + app.targetSdkVersion +
                    " sourcePresent=" + (app.sourceDir != null) +
                    " splits=" + (app.splitSourceDirs == null ? 0 : app.splitSourceDirs.length);
        }
        VirtualCore.getConfig().onLimbusPackageQueries(summary);
    }

    /** Shared with the separate permission service; does not receive or retain query arguments. */
    public static void recordQueryResult(Method method, boolean proxied, Object result, Throwable error) {
        ApplicationInfo current = VClient.get().getCurrentApplicationInfo();
        if (current != null && GAME.equals(current.packageName)) {
            if (error == null && (method.getName().equals("getInstalledPackages")
                    || method.getName().equals("getInstalledApplications"))
                    && ParceledListSliceCompat.isParceledListSlice(result)) {
                try {
                    Object list = ParceledListSliceCompat.getList(result);
                    if (list instanceof java.util.List) result = list;
                } catch (Throwable ignored) {
                    // A passive observer must preserve both the returned object and its exception.
                }
            }
            TRACE.record(method.getName(), proxied, result, error);
            // Some exits have no Java alert. Preserve a small argument-free sample then too.
            boolean sourceSample = (method.getName().equals("getInstallerPackageName") || method.getName().equals("getApplicationEnabledSetting"))
                    && SOURCE_QUERY_REPORTS.getAndIncrement() < 4;
            boolean componentSample = method.getName().equals("getComponentEnabledSetting")
                    && COMPONENT_QUERY_REPORTS.getAndIncrement() < 4;
            if (sourceSample || componentSample) {
                try { VirtualCore.getConfig().onLimbusPackageQueries(TRACE.snapshot()); }
                catch (Throwable ignored) { /* Diagnostic collection must not affect this query. */ }
            }
        }
    }

    /** Called in the game process, before dispatch; never reads the alert's extras. */
    public static void recordBeforeSafetyAlert(Intent intent) {
        try {
            ApplicationInfo current = VClient.get().getCurrentApplicationInfo();
            ComponentName target = intent == null ? null : intent.getComponent();
            if (current != null && GAME.equals(current.packageName)
                    && GAME.equals(VirtualRuntime.getProcessName()) && target != null
                    && LimbusSafetyAlert.isTarget(target.getPackageName(), target.getClassName(), intent.getAction()))
                VirtualCore.getConfig().onLimbusPackageQueries(TRACE.snapshot());
        } catch (Throwable ignored) {
            // Preserve the normal Activity dispatch even when collecting diagnostics fails.
        }
    }

    public PackageManagerStub() {
        super(new MethodInvocationStub<IInterface>(systemPackageManager()) {
            @Override protected Object onInvocationStart(Method method, boolean proxied) { return beginQuery(method, proxied); }
            @Override protected void onInvocationEnd(Object observation, Method method, Object result, Throwable error) {
                endQuery(observation, method, result, error);
            }
            @Override protected void onInvocationResult(Method method, boolean proxied, Object result, Throwable error) {
                recordQueryResult(method, proxied, result, error);
            }
        });
    }

    private static IInterface systemPackageManager() {
        IInterface original = ActivityThread.sPackageManager.get();
        return original == null ? null : PackageManagerUserScope.wrap(original,
                MethodParameterUtils.getAllInterface(original.getClass()),
                VUserHandle::myUserId, VUserHandle::realUserId);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ResultStaticMethodProxy("addPermissionAsync", true));
        addMethodProxy(new ResultStaticMethodProxy("addPermission", true));
        addMethodProxy(new ResultStaticMethodProxy("performDexOpt", true));
        addMethodProxy(new ResultStaticMethodProxy("performDexOptIfNeeded", false));
        addMethodProxy(new ResultStaticMethodProxy("performDexOptSecondary", true));
        addMethodProxy(new ResultStaticMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodProxy(new ResultStaticMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("shouldShowRequestPermissionRationale"));
        if (BuildCompat.isOreo()) {
            addMethodProxy(new ResultStaticMethodProxy("notifyDexLoad", 0));
            addMethodProxy(new ResultStaticMethodProxy("notifyPackageUse", 0));
            addMethodProxy(new ResultStaticMethodProxy("setInstantAppCookie", false));
            addMethodProxy(new ResultStaticMethodProxy("isInstantApp", false));
        }

    }

    @Override
    public void inject() throws Throwable {
        final IInterface hookedPM = mConcreteInterface != null
                ? mConcreteInterface : getInvocationStub().getProxyInterface();
        BinderInvocationStub pmHookBinder = new BinderInvocationStub(getInvocationStub().getBaseInterface()) {
            private final IInterface exposed = mRuntimeContract == null ? getProxyInterface()
                    : RuntimeInterfaceAdapter.wrap(mRuntimeContract, getProxyInterface());
            @Override public IInterface queryLocalInterface(String descriptor) { return exposed; }
            @Override protected Object onInvocationStart(Method method, boolean proxied) { return beginQuery(method, proxied); }
            @Override protected void onInvocationEnd(Object observation, Method method, Object result, Throwable error) {
                endQuery(observation, method, result, error);
            }
            @Override protected void onInvocationResult(Method method, boolean proxied, Object result, Throwable error) {
                recordQueryResult(method, proxied, result, error);
            }
        };
        pmHookBinder.copyMethodProxies(getInvocationStub());
        ActivityThread.sPackageManager.set(hookedPM);
        pmHookBinder.replaceService("package");
        try {
            Context systemContext = Reflect.on(VirtualCore.mainThread()).call("getSystemContext").get();
            Object systemContextPm = Reflect.on(systemContext).field("mPackageManager").get();
            if (systemContextPm != null) {
                Reflect.on(systemContext).field("mPackageManager").set("mPM", hookedPM);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean isEnvBad() {
        IInterface expected = mConcreteInterface != null
                ? mConcreteInterface : getInvocationStub().getProxyInterface();
        return expected != ActivityThread.sPackageManager.get();
    }
}
