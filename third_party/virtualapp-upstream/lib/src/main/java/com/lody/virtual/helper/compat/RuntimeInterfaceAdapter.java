package com.lody.virtual.helper.compat;

import android.annotation.TargetApi;

import com.android.dx.Code;
import com.android.dx.DexMaker;
import com.android.dx.FieldId;
import com.android.dx.Local;
import com.android.dx.MethodId;
import com.android.dx.TypeId;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

import dalvik.system.InMemoryDexClassLoader;

/** A concrete implementation of the device's actual interface, retaining its existing delegate. */
@TargetApi(26)
public final class RuntimeInterfaceAdapter {
    private static final Map<Class<?>, Constructor<?>> CONSTRUCTORS = new HashMap<>();
    private RuntimeInterfaceAdapter() { }

    /**
     * Only emits direct interface calls: no replacement results, argument conversion or exception
     * handling. Reading the runtime interface also covers OEM additions and changes to AIDL flags.
     * Generated code stays in memory; no downloaded code or writable executable cache is involved.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static synchronized <T> T wrap(Class<T> contract, T delegate) throws ReflectiveOperationException {
        if (!contract.isInterface() || !Modifier.isPublic(contract.getModifiers())
                || delegate == null || !contract.isInstance(delegate)) {
            throw new IllegalArgumentException("A public interface and matching delegate are required");
        }
        Constructor<?> cached = CONSTRUCTORS.get(contract);
        if (cached != null) return contract.cast(cached.newInstance(delegate));
        String className = "com.lody.virtual.generated.PackageInterface";
        TypeId generated = TypeId.get("L" + className.replace('.', '/') + ";");
        TypeId api = TypeId.get(contract);
        DexMaker dex = new DexMaker();
        dex.declare(generated, "PackageInterface.generated", Modifier.PUBLIC | Modifier.FINAL,
                TypeId.OBJECT, api);
        FieldId delegateField = generated.getField(api, "delegate");
        dex.declare(delegateField, Modifier.PRIVATE | Modifier.FINAL, null);
        Code constructor = dex.declare(generated.getConstructor(api), Modifier.PUBLIC);
        Local self = constructor.getThis(generated);
        Local argument = constructor.getParameter(0, api);
        constructor.invokeDirect(TypeId.OBJECT.getConstructor(), null, self);
        constructor.iput(delegateField, self, argument);
        constructor.returnVoid();

        Set<MethodId> emitted = new HashSet<>();
        for (Method method : contract.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] parameters = method.getParameterTypes();
            TypeId[] types = new TypeId[parameters.length];
            for (int i = 0; i < types.length; ++i) types[i] = TypeId.get(parameters[i]);
            TypeId resultType = TypeId.get(method.getReturnType());
            MethodId target = generated.getMethod(resultType, method.getName(), types);
            if (!emitted.add(target)) continue;
            Code body = dex.declare(target, Modifier.PUBLIC | Modifier.FINAL);
            Local instance = body.getThis(generated);
            Local receiver = body.newLocal(api);
            Local result = method.getReturnType() == void.class ? null : body.newLocal(resultType);
            Local[] args = new Local[types.length];
            for (int i = 0; i < args.length; ++i) args[i] = body.getParameter(i, types[i]);
            body.iget(delegateField, receiver, instance);
            body.invokeInterface(api.getMethod(resultType, method.getName(), types), result,
                    receiver, args);
            if (result == null) body.returnVoid();
            else body.returnValue(result);
        }

        ClassLoader parent = contract.getClassLoader();
        if (parent == null) parent = RuntimeInterfaceAdapter.class.getClassLoader();
        ClassLoader loader = new InMemoryDexClassLoader(ByteBuffer.wrap(dex.generate()), parent);
        Constructor<?> factory = loader.loadClass(className).getConstructor(contract);
        T adapted = contract.cast(factory.newInstance(delegate));
        CONSTRUCTORS.put(contract, factory);
        return adapted;
    }
}
