package com.lody.virtual.client.fixer;

import android.content.Context;
import android.content.ContextWrapper;

import com.lody.virtual.client.NativeEngine;
import com.lody.virtual.client.VClient;
import com.lody.virtual.helper.compat.ContainerAbiCompat;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Own the guest's external directory creation instead of asking system storage to create a foreign app's path. */
public final class LimbusStorageContext extends ContextWrapper {
    private static final String GAME = "com.ProjectMoon.LimbusCompany";
    private static final AtomicBoolean failureReported = new AtomicBoolean();
    private final boolean physicalPaths;

    private LimbusStorageContext(Context base, boolean physicalPaths) {
        super(base);
        this.physicalPaths = physicalPaths;
    }

    public static Context wrap(Context base, String guestPackage, String[] platformAbis) {
        if (base == null || base instanceof LimbusStorageContext || !GAME.equals(guestPackage)) return base;
        // Derived ContextImpl instances also need their internal files/preferences paths restored.
        VClient.get().applyLimbusVisibleContextDirs(base, GAME);
        return new LimbusStorageContext(base, ContainerAbiCompat.requiresHostContextPaths(platformAbis));
    }

    private File directory(String area, String child, String type) {
        String visible = "/storage/emulated/0/Android/" + area + "/" + GAME + "/";
        String mapped = NativeEngine.getRedirectedPath(visible);
        // An absent mapping must not expose or write into the separately installed official app.
        if (mapped == null || mapped.equals(visible)) return unavailable("mapping_missing");
        File root = child == null ? new File(mapped) : new File(mapped, child);
        File directory = root;
        if (type != null && !type.isEmpty()) {
            directory = new File(root, type);
            try {
                if (new File(type).isAbsolute() || !directory.getCanonicalPath().startsWith(root.getCanonicalPath() + "/"))
                    throw new IllegalArgumentException("External storage type escapes its directory");
            } catch (IOException e) { return unavailable("path_unavailable"); }
        }
        if (!directory.isDirectory() && !directory.mkdirs()) return unavailable("create_failed");
        if (physicalPaths) return directory;
        // Keep the established game-visible path on ARM; its libc maps reads/writes to the same directory.
        File visibleRoot = child == null ? new File(visible) : new File(visible, child);
        return type == null || type.isEmpty() ? visibleRoot : new File(visibleRoot, type);
    }

    private static File unavailable(String reason) {
        if (failureReported.compareAndSet(false, true))
            android.util.Log.w("LimbusVA", "Game external storage unavailable reason=" + reason);
        return null;
    }

    @Override public File getExternalFilesDir(String type) { return directory("data", "files", type); }
    @Override public File[] getExternalFilesDirs(String type) { return new File[]{getExternalFilesDir(type)}; }
    @Override public File getExternalCacheDir() { return directory("data", "cache", null); }
    @Override public File[] getExternalCacheDirs() { return new File[]{getExternalCacheDir()}; }
    @Override public File getObbDir() { return directory("obb", null, null); }
    @Override public File[] getObbDirs() { return new File[]{getObbDir()}; }
    @Override public File[] getExternalMediaDirs() { return new File[]{directory("media", null, null)}; }
}
