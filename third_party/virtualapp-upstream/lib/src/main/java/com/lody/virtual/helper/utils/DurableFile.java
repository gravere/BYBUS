package com.lody.virtual.helper.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Private configuration only. A failed or interrupted write never truncates the committed file. */
public final class DurableFile {
    private DurableFile() { }
    public interface Writer { void write(FileOutputStream output) throws IOException; }

    public static void write(File file, byte[] bytes) throws IOException {
        write(file, output -> output.write(bytes));
    }

    public static synchronized void write(File file, Writer writer) throws IOException {
        Path target = file.toPath();
        Files.createDirectories(target.getParent());
        Path pending = target.resolveSibling(target.getFileName() + ".new");
        try {
            try (FileOutputStream output = new FileOutputStream(pending.toFile())) {
                writer.write(output);
                output.getFD().sync();
            }
            // If this filesystem cannot atomically replace, fail with the previous file intact.
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    public static synchronized byte[] read(File file, int limit) throws IOException {
        long size = Files.size(file.toPath());
        if (size <= 0 || size > limit) throw new IOException("Invalid private configuration size");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) size);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("Private configuration exceeds limit");
                output.write(buffer, 0, count);
            }
            if (output.size() != size) throw new IOException("Private configuration changed during read");
            return output.toByteArray();
        }
    }
}
