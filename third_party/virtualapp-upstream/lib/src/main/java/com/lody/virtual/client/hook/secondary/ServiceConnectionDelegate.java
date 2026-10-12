package com.lody.virtual.client.hook.secondary;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.util.Log;

import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.collection.ArrayMap;
import com.lody.virtual.helper.compat.BuildCompat;
import com.lody.virtual.helper.compat.LimbusBillingDiagnostics;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicInteger;

import mirror.android.app.IServiceConnectionO;

/**
 * @author Lody
 */

public class ServiceConnectionDelegate extends IServiceConnection.Stub {
    private static final String TAG = "LimbusVA";
    private static final String LIMBUS_PACKAGE = "com.ProjectMoon.LimbusCompany";
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String PLAY_STORE_PACKAGE = "com.android.vending";
    private static final String PLAY_BILLING_BINDER_DESCRIPTOR =
            "com.android.vending.billing.IInAppBillingService";
    private static final String MICROG_IDENTITY_AUTHORIZATION_SERVICE =
            "org.microg.gms.auth.credentials.identity.AuthorizationService";
    private final static ArrayMap<IBinder, ServiceConnectionDelegate> DELEGATE_MAP = new ArrayMap<>();
    private IServiceConnection mConn;
    private volatile ComponentName targetComponent;

    private ServiceConnectionDelegate(IServiceConnection mConn, ComponentName targetComponent) {
        this.mConn = mConn;
        this.targetComponent = targetComponent;
    }

    public static ServiceConnectionDelegate getDelegate(IServiceConnection conn) {
        if (conn instanceof ServiceConnectionDelegate) {
            return (ServiceConnectionDelegate) conn;
        }
        return DELEGATE_MAP.get(conn.asBinder());
    }

    public static ServiceConnectionDelegate getOrCreateDelegate(IServiceConnection conn, ComponentName targetComponent) {
        if (conn instanceof ServiceConnectionDelegate) {
            return (ServiceConnectionDelegate) conn;
        }
        final IBinder binder = conn.asBinder();
        ServiceConnectionDelegate delegate = DELEGATE_MAP.get(binder);
        if (delegate == null) {
            try {
                binder.linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        DELEGATE_MAP.remove(binder);
                        binder.unlinkToDeath(this, 0);
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            delegate = new ServiceConnectionDelegate(conn, targetComponent);
            DELEGATE_MAP.put(binder, delegate);
        } else {
            // LoadedApk may reuse the same IServiceConnection binder. Keep the resolved target
            // current (including clearing it) so a previous bind cannot affect this callback.
            delegate.targetComponent = targetComponent;
        }
        return delegate;
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        connected(name, service, false);
    }

    public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        service = wrapLimbusPlayBillingService(name, service);
        service = wrapLimbusGmsServiceBroker(name, service);
        ComponentName deliveryComponent = targetComponent != null ? targetComponent : name;
        if (BuildCompat.isOreo()) {
            IServiceConnectionO.connected.call(mConn, deliveryComponent, service, dead);
        } else {
            mConn.connected(name, service);
        }
    }

    private IBinder wrapLimbusPlayBillingService(ComponentName connectedComponent, IBinder service) {
        if (service == null || !LIMBUS_PACKAGE.equals(VClient.get().getCurrentPackage())) {
            return service;
        }
        String targetPackage = targetComponent == null ? null : targetComponent.getPackageName();
        if (!PLAY_STORE_PACKAGE.equals(targetPackage) && connectedComponent != null) {
            targetPackage = connectedComponent.getPackageName();
        }
        String descriptor = null;
        try {
            descriptor = service.getInterfaceDescriptor();
        } catch (Throwable ignored) {
            // 部分系统 Binder 包装器只有在 queryLocalInterface 时才暴露描述符，交给策略中的组件名兜底。
        }
        if (!shouldWrapLimbusPlayBillingService(
                targetPackage,
                VClient.get().getCurrentPackage(),
                descriptor)) {
            if (PLAY_STORE_PACKAGE.equals(targetPackage)) {
                LimbusBillingDiagnostics.record("event=unwrapped descriptorMatched=false");
            }
            return service;
        }
        try {
            ClassLoader classLoader = VClient.get().getClassLoader();
            if (classLoader == null) {
                return service;
            }
            Log.i(TAG, "Wrap Play billing service for Limbus service=" + targetComponent
                    + " connected=" + connectedComponent
                    + " descriptor=" + descriptor);
            LimbusBillingDiagnostics.record("event=connected environment=container");
            return new StubBinder(VirtualCore.get().getContext(), classLoader, service) {
                @Override
                public InvocationHandler createHandler(Class<?> interfaceClass, IInterface iInterface) {
                    return new LimbusPlayBillingInvocationHandler(iInterface);
                }
            };
        } catch (Throwable error) {
            LimbusBillingDiagnostics.record("event=wrap_failed type=" + error.getClass().getSimpleName());
            Log.w(TAG, "Unable to wrap Play billing service for Limbus service=" + targetComponent, error);
            return service;
        }
    }

    /**
     * 判断当前服务连接是否需要启用边狱公司专用的 Play Billing Binder 包装。
     *
     * <p>组件包名与 Binder 描述符必须同时匹配，且当前虚拟包必须严格为边狱公司，避免把 Play Store
     * 的 Asset Delivery 等其他 Binder 服务误当成计费服务。</p>
     *
     * @param targetPackage 实际绑定或回调组件的包名
     * @param currentPackage 当前虚拟应用包名
     * @param binderDescriptor 服务 Binder 的接口描述符
     * @return 仅当连接属于边狱公司的 Play Billing 服务时返回 {@code true}
     */
    public static boolean shouldWrapLimbusPlayBillingService(
            String targetPackage,
            String currentPackage,
            String binderDescriptor
    ) {
        return LIMBUS_PACKAGE.equals(currentPackage)
                && PLAY_STORE_PACKAGE.equals(targetPackage)
                && PLAY_BILLING_BINDER_DESCRIPTOR.equals(binderDescriptor);
    }

    private IBinder wrapLimbusGmsServiceBroker(ComponentName connectedComponent, IBinder service) {
        if (service == null || !LIMBUS_PACKAGE.equals(VClient.get().getCurrentPackage())) {
            return service;
        }
        String targetPackage = targetComponent == null ? null : targetComponent.getPackageName();
        if (!GMS_PACKAGE.equals(targetPackage) && connectedComponent != null) {
            targetPackage = connectedComponent.getPackageName();
        }
        String descriptor = null;
        try {
            descriptor = service.getInterfaceDescriptor();
        } catch (Throwable ignored) {
            // Some vendor Binder wrappers do not expose the descriptor until queried locally.
        }
        if (!shouldWrapLimbusGmsServiceBroker(
                targetPackage,
                VClient.get().getCurrentPackage(),
                descriptor)) {
            return service;
        }
        try {
            ClassLoader classLoader = VClient.get().getClassLoader();
            if (classLoader == null) {
                return service;
            }
            final boolean preserveLimbusBusinessPackage = targetComponent != null
                    && shouldPreserveLimbusGmsBusinessPackage(
                    targetComponent.getPackageName(), targetComponent.getClassName());
            Log.i(TAG, "Wrap GMS service broker for Limbus service=" + targetComponent
                    + " connected=" + connectedComponent
                    + " descriptor=" + descriptor
                    + " virtualGms=" + VirtualCore.get().isAppInstalled(GMS_PACKAGE));
            return new StubBinder(VirtualCore.get().getContext(), classLoader, service) {
                private IInterface brokerInterface;

                @Override
                public IInterface queryLocalInterface(String descriptor) {
                    if ("com.google.android.gms.common.internal.IGmsServiceBroker".equals(descriptor)) {
                        if (brokerInterface == null) {
                            brokerInterface = createGmsBrokerProxy(
                                    classLoader, getBaseBinder(), preserveLimbusBusinessPackage);
                        }
                        if (brokerInterface != null) {
                            return brokerInterface;
                        }
                    }
                    return super.queryLocalInterface(descriptor);
                }

                @Override
                public InvocationHandler createHandler(Class<?> interfaceClass, IInterface iInterface) {
                    return new LimbusGmsBrokerInvocationHandler(
                            iInterface, preserveLimbusBusinessPackage);
                }
            };
        } catch (Throwable error) {
            Log.w(TAG, "Unable to wrap GMS service broker for Limbus service=" + targetComponent, error);
            return service;
        }
    }

    public static boolean shouldWrapLimbusGmsServiceBroker(
            String targetPackage,
            String currentPackage
    ) {
        return shouldWrapLimbusGmsServiceBroker(targetPackage, currentPackage, null);
    }

    public static boolean shouldWrapLimbusGmsServiceBroker(
            String targetPackage,
            String currentPackage,
            String binderDescriptor
    ) {
        return LIMBUS_PACKAGE.equals(currentPackage)
                && (GMS_PACKAGE.equals(targetPackage)
                || "com.google.android.gms.common.internal.IGmsServiceBroker".equals(binderDescriptor));
    }

    public static boolean shouldPreserveLimbusGmsBusinessPackage(
            String targetPackage,
            String targetClassName
    ) {
        return GMS_PACKAGE.equals(targetPackage)
                && MICROG_IDENTITY_AUTHORIZATION_SERVICE.equals(targetClassName);
    }

    private static IInterface createGmsBrokerProxy(
            ClassLoader classLoader,
            IBinder baseBinder,
            boolean preserveLimbusBusinessPackage
    ) {
        try {
            Class<?> interfaceClass = classLoader.loadClass("com.google.android.gms.common.internal.IGmsServiceBroker");
            Class<?> stubClass = classLoader.loadClass("com.google.android.gms.common.internal.IGmsServiceBroker$Stub");
            Method asInterface = null;
            for (Method method : stubClass.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0] == IBinder.class
                        && IInterface.class.isAssignableFrom(method.getReturnType())) {
                    asInterface = method;
                    break;
                }
            }
            if (asInterface == null) {
                Log.i(TAG, "IGmsServiceBroker Stub factory missing; try direct proxy constructor");
                IInterface base = createGmsBrokerBaseFromKnownProxy(classLoader, interfaceClass, baseBinder);
                return createGmsBrokerProxyInterface(
                        classLoader, interfaceClass, base, preserveLimbusBusinessPackage);
            }
            asInterface.setAccessible(true);
            IInterface base = (IInterface) asInterface.invoke(null, baseBinder);
            return createGmsBrokerProxyInterface(
                    classLoader, interfaceClass, base, preserveLimbusBusinessPackage);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to create explicit Limbus GMS broker proxy", error);
            return null;
        }
    }

    private static IInterface createGmsBrokerBaseFromKnownProxy(
            ClassLoader classLoader,
            Class<?> interfaceClass,
            IBinder baseBinder
    ) throws Exception {
        Class<?> proxyClass = classLoader.loadClass("com.google.android.gms.common.internal.zzaa");
        if (!interfaceClass.isAssignableFrom(proxyClass)) {
            throw new ClassCastException(proxyClass.getName() + " is not IGmsServiceBroker");
        }
        Constructor<?> constructor = proxyClass.getDeclaredConstructor(IBinder.class);
        constructor.setAccessible(true);
        return (IInterface) constructor.newInstance(baseBinder);
    }

    private static IInterface createGmsBrokerProxyInterface(
            ClassLoader classLoader,
            Class<?> interfaceClass,
            IInterface base,
            boolean preserveLimbusBusinessPackage
    ) {
        IInterface proxy = (IInterface) Proxy.newProxyInstance(
                classLoader,
                new Class<?>[]{interfaceClass},
                new LimbusGmsBrokerInvocationHandler(base, preserveLimbusBusinessPackage)
        );
        Log.i(TAG, "Created explicit Limbus GMS broker proxy");
        return proxy;
    }

    private static final class LimbusGmsBrokerInvocationHandler implements InvocationHandler {
        private final IInterface base;
        private final boolean preserveLimbusBusinessPackage;

        private LimbusGmsBrokerInvocationHandler(
                IInterface base,
                boolean preserveLimbusBusinessPackage
        ) {
            this.base = base;
            this.preserveLimbusBusinessPackage = preserveLimbusBusinessPackage;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getService".equals(method.getName()) && args != null) {
                for (Object arg : args) {
                    rewriteGetServiceRequestPackage(arg, preserveLimbusBusinessPackage);
                }
            }
            try {
                return method.invoke(base, args);
            } catch (InvocationTargetException error) {
                throw error.getTargetException();
            }
        }
    }

    private static final class LimbusPlayBillingInvocationHandler implements InvocationHandler {
        private final IInterface base;
        private static final AtomicInteger diagnosticCount = new AtomicInteger();

        private LimbusPlayBillingInvocationHandler(IInterface base) {
            this.base = base;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Observe only. The container store must receive the game's real package for
            // capability checks, products, ownership and purchase callbacks alike.
            try {
                Object result = method.invoke(base, args);
                // Only protocol metadata: never log arguments, product IDs,
                // account names, purchase receipts, tokens or PendingIntents.
                if (diagnosticCount.getAndIncrement() < 64) {
                  try {
                    String response = "other";
                    int details = -1;
                    if (result instanceof Integer) {
                        response = result.toString();
                    } else if (result instanceof Bundle) {
                        Bundle bundle = (Bundle) result;
                        Object code = bundle.get("RESPONSE_CODE");
                        response = code instanceof Number ? code.toString() : "absent";
                        java.util.ArrayList<String> products = bundle.getStringArrayList("DETAILS_LIST");
                        details = products == null ? -1 : products.size();
                    }
                    String metadata = "method=" + method.getName()
                            + " code=" + response + " details=" + details
                            + " capabilityPackageRewritten=false"
                            + LimbusBillingDiagnostics.describeCall(args, result);
                    LimbusBillingDiagnostics.record(metadata);
                    Log.i(TAG, "Play billing response " + metadata);
                  } catch (RuntimeException diagnosticError) {
                    LimbusBillingDiagnostics.record("event=metadata_unavailable type="
                            + diagnosticError.getClass().getSimpleName());
                    // Observation must never change the real service result.
                    Log.w(TAG, "Play billing metadata unavailable type="
                            + diagnosticError.getClass().getSimpleName());
                  }
                }
                return result;
            } catch (InvocationTargetException error) {
                if (diagnosticCount.getAndIncrement() < 64) {
                    LimbusBillingDiagnostics.record("event=service_failed method=" + method.getName()
                            + " type=" + error.getTargetException().getClass().getSimpleName());
                    Log.w(TAG, "Play billing failure method=" + method.getName()
                            + " type=" + error.getTargetException().getClass().getSimpleName());
                }
                throw error.getTargetException();
            }
        }

    }

    private static void rewriteGetServiceRequestPackage(
            Object request,
            boolean preserveLimbusBusinessPackage
    ) {
        if (request == null
                || !"com.google.android.gms.common.internal.GetServiceRequest".equals(request.getClass().getName())) {
            return;
        }
        if (preserveLimbusBusinessPackage) {
            Log.i(TAG, "Preserve Limbus package for virtual microG Identity Authorization");
            return;
        }
        int replaced = 0;
        Class<?> current = request.getClass();
        while (current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (LIMBUS_PACKAGE.equals(field.get(request))) {
                        field.set(request, VirtualCore.get().getHostPkg());
                        replaced++;
                    }
                } catch (Throwable ignored) {
                    // Ignore inaccessible fields; the request may vary across Play Services versions.
                }
            }
            current = current.getSuperclass();
        }
        if (replaced > 0) {
            Log.i(TAG, "Rewrite Limbus GMS GetServiceRequest package fields=" + replaced
                    + " host=" + VirtualCore.get().getHostPkg());
        }
    }
}
