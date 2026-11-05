package com.lody.virtual.helper.compat;

import android.app.IBinderSession;
import android.app.IServiceConnection;
import android.content.ComponentName;
import android.os.IBinder;
import android.os.RemoteException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Match the loaded framework interface, including OEM backports, rather than its SDK number. */
public final class ServiceConnectionCompat {
    private ServiceConnectionCompat() {}

    private static final class RuntimeCallback {
        static final Method CONNECTED = resolve(IServiceConnection.class);
    }

    public static void connected(IServiceConnection receiver, ComponentName name, IBinder service,
                                 IBinderSession session, boolean dead) throws RemoteException {
        forward(RuntimeCallback.CONNECTED, receiver, name, service, session, dead);
    }

    static Method resolve(Class<?> contract) {
        Class<?>[][] signatures = {
                {ComponentName.class, IBinder.class, IBinderSession.class, boolean.class},
                {ComponentName.class, IBinder.class, boolean.class},
                {ComponentName.class, IBinder.class}
        };
        for (Class<?>[] signature : signatures) {
            try {
                Method method = contract.getMethod("connected", signature);
                if (method.getReturnType() == void.class && !Modifier.isStatic(method.getModifiers())) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // Older framework contracts legitimately have fewer arguments.
            }
        }
        throw new IllegalStateException("Unsupported IServiceConnection.connected signature");
    }

    static void forward(Method method, Object receiver, ComponentName name, IBinder service,
                        IBinderSession session, boolean dead) throws RemoteException {
        Object[] arguments;
        switch (method.getParameterTypes().length) {
            case 4: arguments = new Object[]{name, service, session, dead}; break;
            case 3: arguments = new Object[]{name, service, dead}; break;
            case 2: arguments = new Object[]{name, service}; break;
            default: throw new IllegalStateException("Unsupported service connection callback");
        }
        try {
            method.invoke(receiver, arguments);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RemoteException) throw (RemoteException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Service connection callback failed", cause);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Service connection callback is inaccessible", error);
        }
    }
}
