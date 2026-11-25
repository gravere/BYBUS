package com.example.limbuszhcn

import android.content.pm.ApplicationInfo
import android.os.Process
import android.util.Log
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.server.am.VActivityManagerService
import org.json.JSONObject

/** Synthetic PID registration in the existing server; never launches the game or loads its data. */
internal object GuestFaultProviderProbe {
    private var fixture: Any? = null

    fun register(pid: Int, prepare: Boolean): JSONObject {
        check(VActivityManager.get().systemPid == Process.myPid())
        val service = VActivityManagerService.get()
        val lock = service.javaClass.getDeclaredField("mProcessLock").apply { isAccessible = true }.get(service)
        val field = service.javaClass.getDeclaredField("mPidsSelfLocked").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val records = field.get(service) as MutableList<Any>
        synchronized(lock) {
            if (prepare) {
                check(pid > 0 && pid != Process.myPid() && fixture == null)
                check(service.getInitialPackage(pid) == null)
                val type = Class.forName("com.lody.virtual.server.am.ProcessRecord")
                val constructor = type.declaredConstructors.single().apply { isAccessible = true }
                val info = ApplicationInfo().apply { packageName = "com.ProjectMoon.LimbusCompany" }
                val record = constructor.newInstance(info, info.packageName, 19001, 0, 0, false)
                type.getDeclaredField("pid").apply { isAccessible = true }.setInt(record, pid)
                records.add(record)
                fixture = record
                return JSONObject().put("passed", true).put("pid", pid).put("prepared", true)
            }
            fixture?.let { record -> check(records.remove(record)); fixture = null }
            return JSONObject().put("passed", true).put("registry_restored", true)
        }
    }

    fun read(): JSONObject {
        val since = System.currentTimeMillis() - 1000
        Log.e("Unity", "NullReferenceException: PRIVATE_GUEST_LOG_SENTINEL")
        val snapshot = requireNotNull(requestGameFaultSnapshot(since)) { "Registered guest was rejected" }
        check(snapshot.getString("status") == "available") { "Collector: ${snapshot.getString("status")}" }
        val lines = snapshot.getStringArrayList("markers").orEmpty()
        check(lines.any { it.contains("event=managed_exception type=NullReferenceException") })
        check(lines.all { it.contains(" pid=${Process.myPid()} ") && !it.contains("PRIVATE_GUEST_LOG_SENTINEL") })
        return JSONObject().put("passed", true).put("pid", Process.myPid())
            .put("collected_over_binder", true).put("private_payload_removed", true)
            .put("markers", lines.size).put("game_started", false)
    }
}
