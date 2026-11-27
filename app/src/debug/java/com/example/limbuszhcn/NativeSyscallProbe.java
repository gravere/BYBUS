package com.example.limbuszhcn;

/** Self-contained debug fixture: no game, account or container is started. */
public final class NativeSyscallProbe {
    static { System.loadLibrary("bybus_syscall_probe"); }
    public static native String check();
}
