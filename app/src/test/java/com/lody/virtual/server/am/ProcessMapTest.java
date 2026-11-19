package com.lody.virtual.server.am;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProcessMapTest {
    @Test public void delayedDeathCanOnlyRemoveItsOwnRegistration() {
        ProcessMap<String> processes = new ProcessMap<>();
        String old = new String("game");
        String replacement = new String("game");
        processes.put("game", 10, old);
        processes.put("game", 11, old);
        processes.put("game", 10, replacement);
        processes.removeIfCurrent("game", 10, old);
        assertSame(replacement, processes.get("game", 10));
        processes.removeIfCurrent("game", 10, replacement);
        assertNull(processes.get("game", 10));
        assertSame(old, processes.get("game", 11));
    }
}
