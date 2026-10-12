package com.lody.virtual.helper.compat;

import android.content.Context;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.tukaani.xz.XZInputStream;

/** Host-worker preparation; guest Application binding must never decompress assets. */
public final class LimbusTranslationAssets {
    public static final String FONT_PATH = "translation-cache/runtime-font/ChineseFont-a56a06f1.ttf";
    public static final long FONT_SIZE = 23870096L;
    private static final String FONT_SHA256 = "a56a06f1af27726bc5def015b61deecbbdf5ae6d954a91b1131d8a17290def35";

    private LimbusTranslationAssets() {}

    public static boolean isMainGameProcess(String packageName, String processName) {
        return "com.ProjectMoon.LimbusCompany".equals(packageName) && packageName.equals(processName);
    }

    public static boolean isPrepared(Context host) {
        File font = new File(host.getFilesDir(), FONT_PATH);
        return font.isFile() && font.length() == FONT_SIZE;
    }

    public static File prepare(Context host) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Translation assets must be prepared on the launch worker");
        }
        return prepareVerified(new File(host.getFilesDir(), FONT_PATH).toPath(), FONT_SIZE, FONT_SHA256,
                () -> new XZInputStream(host.getAssets().open("runtime/ChineseFont.ttf.xz"), 16 * 1024));
    }

    public interface FontInput { InputStream open() throws IOException; }

    /** Exposed for desktop regression tests; publication occurs only after complete verification. */
    public static File prepareVerified(Path target, long expectedSize, String expectedSha256,
                                       FontInput decodedInput) throws IOException {
        if (Files.isRegularFile(target) && Files.size(target) == expectedSize) {
            try (InputStream input = Files.newInputStream(target)) {
                if (expectedSha256.equals(hash(input))) return target.toFile();
            }
        }
        Files.createDirectories(target.getParent());
        Path pending = Files.createTempFile(target.getParent(), ".font-", ".tmp");
        try {
            MessageDigest digest = sha256();
            long total = 0;
            try (InputStream input = decodedInput.open(); FileOutputStream output = new FileOutputStream(pending.toFile())) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Font preparation cancelled");
                    total += count;
                    if (total > expectedSize) throw new IOException("Font exceeds expected size");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (total != expectedSize || !expectedSha256.equals(hex(digest.digest()))) {
                throw new IOException("Bundled translation font validation failed");
            }
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target.toFile();
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static String hash(InputStream input) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        return hex(digest.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value & 255) >>> 4, 16));
            out.append(Character.forDigit(value & 15, 16));
        }
        return out.toString();
    }
}
