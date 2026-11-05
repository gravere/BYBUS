package com.lody.virtual.helper.utils;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/** The host's wait may end before the server's transaction. Keep writers fenced until both settle. */
public final class PackageInstallGuard {
    private static final AtomicInteger pending = new AtomicInteger();
    private static final ReentrantLock serverLock = new ReentrantLock();
    private static final String BUSY = "上次容器安装仍在处理，请稍后重试；已有数据已保留";
    private PackageInstallGuard() { }
    public interface Task<T> { T run() throws Exception; }

    private static FileChannel channel(File root) throws IOException {
        Files.createDirectories(root.toPath());
        return FileChannel.open(root.toPath().resolve("virtual-package-install.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    public static void assertIdle(File root) throws IOException {
        if (pending.get() != 0) throw new IllegalStateException(BUSY);
        try (FileChannel channel = channel(root)) {
            try (FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IllegalStateException(BUSY);
            } catch (OverlappingFileLockException busy) { throw new IllegalStateException(BUSY); }
        }
    }

    /** Server-process lock survives the waiting host being recreated; the OS releases it on server death. */
    public static <T> T runServer(File root, Task<T> task) throws Exception {
        serverLock.lockInterruptibly();
        try (FileChannel channel = channel(root); FileLock ignored = channel.lock()) {
            return task.run();
        } finally { serverLock.unlock(); }
    }

    public static final class Pending<T> {
        private final CountDownLatch completed = new CountDownLatch(1);
        private T result;
        private RuntimeException failure;
        public Pending() { pending.incrementAndGet(); }
        public synchronized void complete(T result) { finish(result, null); }
        public synchronized void fail(RuntimeException error) { finish(null, error); }
        private void finish(T value, RuntimeException error) {
            if (isDone()) return;
            result = value;
            failure = error;
            pending.decrementAndGet();
            completed.countDown();
        }
        public boolean isDone() { return completed.getCount() == 0; }
        public T await(long timeoutMillis) {
            try {
                if (!completed.await(timeoutMillis, TimeUnit.MILLISECONDS))
                    throw new IllegalStateException("容器安装等待超时，后台任务仍受保护；请稍后重试");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("容器安装等待已中断，后台任务仍受保护", interrupted);
            }
            if (failure != null) throw failure;
            if (result == null) throw new IllegalStateException("容器未返回安装结果");
            return result;
        }
    }
}
