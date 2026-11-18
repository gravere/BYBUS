package com.lody.virtual.helper.compat;

import java.io.File;
import java.util.regex.Pattern;

/** Android pairs a split's manifest name and its installed path by array index. */
public final class AssetPackNames {
    private static final Pattern PACK_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private AssetPackNames() {}

    public static String nameAt(String[] names, String[] paths, int index) {
        if (paths == null || index < 0 || index >= paths.length || paths[index] == null) return null;
        String name;
        if (names != null) {
            if (names.length != paths.length) return null;
            name = names[index];
        } else {
            String file = new File(paths[index]).getName();
            if (!file.startsWith("split_") || !file.endsWith(".apk")) return null;
            name = file.substring(6, file.length() - 4);
        }
        return name != null && PACK_NAME.matcher(name).matches() ? name : null;
    }
}
