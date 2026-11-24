package com.lody.virtual.helper.compat;

import java.io.File;
import java.util.function.BiConsumer;

/** Virtual user paths are independent of the Android user hosting the container. */
public final class LimbusStoragePaths {
    public static final String GAME = "com.ProjectMoon.LimbusCompany";
    private static final String[] AREAS = {"data", "obb", "media"};

    private LimbusStoragePaths() {}

    public static String relativePackageDirectory(String area) {
        if (!"data".equals(area) && !"obb".equals(area) && !"media".equals(area))
            throw new IllegalArgumentException("Unsupported game storage area");
        return "Android/" + area + "/" + GAME + "/";
    }

    public static String visiblePackageDirectory(String area) {
        return "/storage/emulated/0/" + relativePackageDirectory(area);
    }

    public static void redirectPackageDirectories(String packageName, File virtualStorage,
                                                   BiConsumer<String, String> redirect) {
        if (!GAME.equals(packageName)) return;
        for (String area : AREAS) {
            redirect.accept(visiblePackageDirectory(area),
                    new File(virtualStorage, relativePackageDirectory(area)).getAbsolutePath());
        }
    }
}
