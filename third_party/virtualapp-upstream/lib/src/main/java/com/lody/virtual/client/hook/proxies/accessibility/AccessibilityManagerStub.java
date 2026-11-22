package com.lody.virtual.client.hook.proxies.accessibility;

import android.content.Context;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.StaticMethodProxy;

import java.lang.reflect.Method;

import mirror.android.view.accessibility.IAccessibilityManager;

/**
 * @author Lody
 */
public class AccessibilityManagerStub extends BinderInvocationProxy {

    public AccessibilityManagerStub() {
        super(IAccessibilityManager.Stub.TYPE, Context.ACCESSIBILITY_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ReplaceLastUserIdProxy("addClient"));
        addMethodProxy(new ReplaceLastUserIdProxy("removeClient"));
        addMethodProxy(new ReplaceLastUserIdProxy("sendAccessibilityEvent"));
        addMethodProxy(new ReplaceLastUserIdProxy("getInstalledAccessibilityServiceList"));
        addMethodProxy(new ReplaceLastUserIdProxy("getEnabledAccessibilityServiceList"));
        addMethodProxy(new ReplaceLastUserIdProxy("getWindowToken"));
        addMethodProxy(new ReplaceLastUserIdProxy("interrupt"));
        addMethodProxy(new ReplaceLastUserIdProxy("addAccessibilityInteractionConnection"));
    }

    private static class ReplaceLastUserIdProxy extends StaticMethodProxy {

        public ReplaceLastUserIdProxy(String name) {
            super(name);
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return super.call(who, method, com.lody.virtual.helper.compat.FrameworkUserScope.arguments(
                    method, args, getAppUserId(), getRealUserId()));
        }
    }
}
