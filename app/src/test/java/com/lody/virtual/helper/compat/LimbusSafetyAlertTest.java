package com.lody.virtual.helper.compat;

import org.junit.Test;
import static org.junit.Assert.*;

public class LimbusSafetyAlertTest {
    private static final String GAME = "com.ProjectMoon.LimbusCompany";
    private static final String ALERT = "com.inka.appsealing.AppSealingAlertDialog";

    @Test public void onlyTheRegisteredGameMainProcessCanReportItsOwnExactAlert() {
        assertTrue(LimbusSafetyAlert.matches(GAME, GAME, GAME, ALERT, "controller"));
        assertFalse(LimbusSafetyAlert.matches("other.app", GAME, GAME, ALERT, "controller"));
        assertFalse(LimbusSafetyAlert.matches(GAME, GAME + ":alert_dialog", GAME, ALERT, "controller"));
        assertFalse(LimbusSafetyAlert.matches(GAME, GAME, "other.app", ALERT, "controller"));
        assertFalse(LimbusSafetyAlert.matches(GAME, GAME, GAME, ALERT + "$Other", "controller"));
        assertFalse(LimbusSafetyAlert.matches(GAME, GAME, GAME, ALERT, "other"));
    }

    @Test public void keepsOnlyExplicitErrorCodesAndFixedCategories() {
        LimbusSafetyAlert.Summary result = LimbusSafetyAlert.summarize(2,
                "Integrity error [Error Code: 30010], (30009), 错误编号：50040, code=30010. "
                        + "user@example.test token=private-value /private/report.xml device12345 12345");
        assertEquals("30010,30009,50040", result.codes);
        assertEquals("integrity", result.category);
        assertEquals(2, result.type);
        String text = result.toString();
        for (String forbidden : new String[]{"example", "private", "report.xml", "12345", "token"}) {
            assertFalse(text.contains(forbidden));
        }
        assertTrue(result.messagePresent);
        assertFalse(result.messageTruncated);
    }

    @Test public void malformedAndOversizedMessagesStayBounded() {
        assertEquals("", LimbusSafetyAlert.summarize(1, "code=500401 or 12345").codes);
        assertEquals(-1, LimbusSafetyAlert.summarize(Integer.MAX_VALUE, null).type);
        assertFalse(LimbusSafetyAlert.summarize(1, null).messagePresent);
        LimbusSafetyAlert.Summary large = LimbusSafetyAlert.summarize(1,
                new String(new char[4096]).replace('\0', 'x') + "code=50040 secret");
        assertTrue(large.messageTruncated);
        assertEquals("", large.codes);
        StringBuilder many = new StringBuilder();
        for (int i = 10000; i < 10020; i++) many.append("code=").append(i).append(' ');
        assertEquals(8, LimbusSafetyAlert.summarize(1, many.toString()).codes.split(",").length);
    }
}
