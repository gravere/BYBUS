package com.lody.virtual.client.hook.proxies.connectivity;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.helper.compat.ConnectivityCallerIdentity;

import java.lang.reflect.Method;

/**
 * @Date 18-1-20 15
 * @Author lxf@xdja.com
 * @Descrip:
 */
public class MethodProxies {

    static class GetNetworkCapabilities extends MethodProxy {
        @Override public String getMethodName() { return "getNetworkCapabilities"; }

        @Override public boolean beforeCall(Object who, Method method, Object... args) {
            // WebView may keep a manager created from a derived Google Context. Fixing only
            // the Application Context or the game's cached manager misses that caller.
            ConnectivityCallerIdentity.rewriteCapabilitiesCaller(method, args, getAppPkg(), getHostPkg());
            return true;
        }

        @Override public boolean isEnable() { return isAppProcess(); }
    }

    static class PrepareVpn extends MethodProxy {

        @Override
        public String getMethodName() {
            return "prepareVpn";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {

            args[0] = VirtualCore.get().getHostPkg();
//          args[2] = getRealUid();

            String oldname = (String) args[0];
            String newName = (String) args[1];
            int userid = (int)args[2];

            return method.invoke(who, args);
        }

        @Override
        public Object afterCall(Object who, Method method, Object[] args, Object result) throws Throwable {
            String oldname = (String) args[0];
//            if(oldname.equals("com.xdja.safeclient"))
//                return true;

            return super.afterCall(who, method, args, result);
        }

        @Override
        public boolean isEnable() {
            return isAppProcess();
        }
    }

}
