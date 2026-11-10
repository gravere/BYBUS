package com.lody.virtual.client.hook.proxies.pm;

import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.os.IInterface;

import com.lody.virtual.client.VClient;
import com.lody.virtual.client.env.VirtualRuntime;
import com.lody.virtual.client.core.VirtualCore;
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
import com.lody.virtual.helper.compat.ParceledListSliceCompat;
import java.lang.reflect.Method;

import mirror.android.app.ActivityThread;

/**
 * @author Lody
 */
@Inject(MethodProxies.class)
public final class PackageManagerStub extends MethodInvocationProxy<MethodInvocationStub<IInterface>> {
    private static final String GAME = "com.ProjectMoon.LimbusCompany";
    private static final PackageQueryTrace TRACE = new PackageQueryTrace();

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
        super(new MethodInvocationStub<IInterface>(ActivityThread.sPackageManager.get()) {
            @Override protected void onInvocationResult(Method method, boolean proxied, Object result, Throwable error) {
                recordQueryResult(method, proxied, result, error);
            }
        });
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
        final IInterface hookedPM = getInvocationStub().getProxyInterface();
        ActivityThread.sPackageManager.set(hookedPM);
        BinderInvocationStub pmHookBinder = new BinderInvocationStub(getInvocationStub().getBaseInterface()) {
            @Override protected void onInvocationResult(Method method, boolean proxied, Object result, Throwable error) {
                recordQueryResult(method, proxied, result, error);
            }
        };
        pmHookBinder.copyMethodProxies(getInvocationStub());
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
        return getInvocationStub().getProxyInterface() != ActivityThread.sPackageManager.get();
    }
}
