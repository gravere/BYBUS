package com.lody.virtual.server.am;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ServiceProcessOwnershipTest {
    @Test public void oldBinderDeathDoesNotRemoveANewInstancesService() throws Exception {
        ActiveServices services = new ActiveServices(null);
        ApplicationInfo app = new ApplicationInfo();
        app.packageName = "local.test.serviceowner";
        app.processName = app.packageName;
        ServiceInfo service = new ServiceInfo();
        service.packageName = app.packageName;
        service.name = app.packageName + ".ExampleService";
        service.processName = app.processName;
        service.applicationInfo = app;
        ProcessRecord old = new ProcessRecord(app, app.processName, 10001, 1, 0, false);
        ProcessRecord current = new ProcessRecord(app, app.processName, 10001, 1, 0, false);
        old.pid = current.pid = 312;
        Method getUser = ActiveServices.class.getDeclaredMethod("getUserSpace", int.class);
        getUser.setAccessible(true);
        Object user = getUser.invoke(services, 0);
        Method register = user.getClass().getDeclaredMethod("getOrCreateRunningServiceInfo", ServiceInfo.class, ProcessRecord.class);
        register.setAccessible(true);
        Object first = register.invoke(user, service, old);
        Object second = register.invoke(user, service, current);
        assertNotSame(first, second);
        Field records = user.getClass().getDeclaredField("mRunningServices");
        records.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) records.get(user);
        ComponentName component = new ComponentName(service.packageName, service.name);
        services.processDied(old);
        assertSame(second, map.get(component));
        services.processDied(current);
        assertFalse(map.containsKey(component));
    }
}
