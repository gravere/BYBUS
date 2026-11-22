package com.lody.virtual.helper.compat;

/** Unknown packages must not acquire an installer merely by being queried. */
public final class InstallerSourcePolicy {
    private InstallerSourcePolicy() { }
    public interface Source { String read() throws Throwable; }

    public static String query(boolean virtualPackage, boolean hostVisible, boolean importedFromHost,
                               Source source) throws Throwable {
        if (!virtualPackage && !hostVisible) throw new IllegalArgumentException("Package is not installed");
        // Bundled container components have no system installation source. In particular,
        // never report the source of a different host Google package with the same name.
        if (virtualPackage && !importedFromHost) return null;
        try { return source.read(); }
        catch (IllegalArgumentException missingHostPackage) {
            if (virtualPackage) return null;
            throw missingHostPackage;
        }
    }
}
