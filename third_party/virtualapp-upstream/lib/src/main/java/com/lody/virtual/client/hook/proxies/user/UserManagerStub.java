package com.lody.virtual.client.hook.proxies.user;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.ReplaceCallingPkgMethodProxy;
import com.lody.virtual.client.hook.base.ResultStaticMethodProxy;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.helper.compat.PhysicalUserPolicy;
import java.lang.reflect.Method;

import java.util.Collections;

import mirror.android.content.pm.UserInfo;
import mirror.android.os.IUserManager;

/**
 * @author Lody
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
public class UserManagerStub extends BinderInvocationProxy {

    public UserManagerStub() {
        super(IUserManager.Stub.asInterface, Context.USER_SERVICE);
    }

    @Override
    protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new ProfileStateQuery("isUserUnlocked"));
        addMethodProxy(new ProfileStateQuery("isUserUnlockingOrUnlocked"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("setApplicationRestrictions"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("getApplicationRestrictions"));
        addMethodProxy(new ReplaceCallingPkgMethodProxy("getApplicationRestrictionsForUser"));
        addMethodProxy(new ResultStaticMethodProxy("getProfileParent", null));
        addMethodProxy(new ResultStaticMethodProxy("getUserIcon", null));
        addMethodProxy(new ResultStaticMethodProxy("getUserInfo", UserInfo.ctor.newInstance(0, "Admin", UserInfo.FLAG_PRIMARY.get())));
        addMethodProxy(new ResultStaticMethodProxy("getDefaultGuestRestrictions", null));
        addMethodProxy(new ResultStaticMethodProxy("setDefaultGuestRestrictions", null));
        addMethodProxy(new ResultStaticMethodProxy("removeRestrictions", null));
        addMethodProxy(new ResultStaticMethodProxy("getUsers", Collections.singletonList(UserInfo.ctor.newInstance(0, "Admin", UserInfo.FLAG_PRIMARY.get()))));
        addMethodProxy(new ResultStaticMethodProxy("createUser", null));
        addMethodProxy(new ResultStaticMethodProxy("createProfileForUser", null));
        addMethodProxy(new ResultStaticMethodProxy("getProfiles", Collections.EMPTY_LIST));
    }

    private static final class ProfileStateQuery extends MethodProxy {
        private final String name;
        ProfileStateQuery(String name) { this.name = name; }
        @Override public String getMethodName() { return name; }
        @Override public Object call(Object who, Method method, Object... args) throws Throwable {
            if (isAppProcess() && method.getReturnType() == boolean.class
                    && method.getParameterTypes().length == 1 && method.getParameterTypes()[0] == int.class
                    && args.length == 1 && args[0] instanceof Integer) {
                args[0] = PhysicalUserPolicy.systemUser(getAppPkg(), (Integer) args[0], getAppUserId(), getRealUserId());
            }
            return super.call(who, method, args);
        }
    }
}
