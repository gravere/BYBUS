package io.github.gravere.bybus.migration.core;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

/** Private, authenticated IPC transport. Never accepts an arbitrary source/target directory from an Intent. */
public final class MigrationArchive {
    public static final String LEGACY = "com.example.limbuszhcn";
    public static final String TARGET = "io.github.gravere.bybus";
    public static final String AUTHORITY = LEGACY + ".bybus.migration";
    public static final int MAX_FILES = 250_000;
    public static final long MAX_TOTAL = 512L * 1024 * 1024 * 1024;
    private static final int MAGIC = 0x42594d31;
    private static final String[] PACKAGES = {"com.ProjectMoon.LimbusCompany", "com.google.android.gms", "com.android.vending"};
    private MigrationArchive() { }
    public interface Progress { void update(long copied, long total) throws IOException; }
    public static final class Entry {
        public final String path;
        public final long size, modified;
        Entry(String path, long size, long modified) { this.path=path; this.size=size; this.modified=modified; }
    }
    public static final class Snapshot {
        public final List<Entry> entries;
        public final long bytes;
        Snapshot(List<Entry> entries, long bytes) { this.entries=Collections.unmodifiableList(entries); this.bytes=bytes; }
    }
    public static boolean allowed(String relative) {
        if (relative == null || relative.length() > 4096 || relative.startsWith("/") || relative.contains("\\") || relative.contains(":")) return false;
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
            for (int i=0; i<part.length(); i++) if (Character.isISOControl(part.charAt(i))) return false;
            if (part.equals("cache") || part.equals("code_cache") || part.equals("lib") || part.equals("app_chimera")) return false;
        }
        if (relative.equals("virtual/data/app/system/account-list.ini")
                || relative.equals("virtual/data/app/system/account-visibility-list.ini")) return true;
        if (relative.startsWith("files/translation-cache/"))
            return !relative.startsWith("files/translation-cache/redirect-gate.pid");
        for (String pkg : PACKAGES) {
            if (relative.startsWith("virtual/data/user/0/"+pkg+"/")
                    || relative.startsWith("virtual/data/user_de/0/"+pkg+"/")
                    || relative.startsWith("virtual/storage/emulated/0/Android/data/"+pkg+"/")) return true;
        }
        return false;
    }
    public static Path resolve(Path root, String relative) throws IOException {
        if (!allowed(relative)) throw new IOException("迁移包含不支持的文件路径");
        Path base=root.toAbsolutePath().normalize(), target=base.resolve(relative).normalize();
        if (!target.startsWith(base)) throw new IOException("迁移路径越界");
        Path current=base;
        for (Path part : base.relativize(target)) {
            current=current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("迁移路径包含符号链接");
        }
        return target;
    }
    public static Snapshot snapshot(Path root) throws IOException {
        Path base=root.toAbsolutePath().normalize();
        List<Entry> entries=new ArrayList<>();
        long total=0;
        if (!Files.isDirectory(base)) return new Snapshot(entries, 0);
        try (Stream<Path> paths=Files.walk(base)) {
            Iterator<Path> iterator=paths.iterator();
            while (iterator.hasNext()) {
                Path file=iterator.next();
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative=base.relativize(file).toString().replace(File.separatorChar, '/');
                if (!allowed(relative)) continue;
                resolve(base, relative);
                long size=Files.size(file);
                if (size<0 || size>MAX_TOTAL-total || entries.size()==MAX_FILES) throw new IOException("迁移数据超出支持范围");
                total+=size;
                entries.add(new Entry(relative,size,Files.getLastModifiedTime(file).toMillis()));
            }
        }
        entries.sort(Comparator.comparing(e -> e.path));
        return new Snapshot(entries,total);
    }
    public static void write(Path root, Snapshot snapshot, OutputStream stream) throws IOException {
        DataOutputStream output=new DataOutputStream(new BufferedOutputStream(stream,131072));
        output.writeInt(MAGIC); output.writeInt(snapshot.entries.size()); output.writeLong(snapshot.bytes);
        byte[] buffer=new byte[131072];
        for (Entry entry : snapshot.entries) {
            Path file=resolve(root, entry.path);
            if (Files.size(file)!=entry.size || Files.getLastModifiedTime(file).toMillis()!=entry.modified)
                throw new IOException("旧数据已变化，请重新开始迁移");
            output.writeUTF(entry.path); output.writeLong(entry.size);
            MessageDigest hash=sha256(); long remaining=entry.size;
            try (InputStream input=Files.newInputStream(file)) {
                while (remaining>0) {
                    int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));
                    if (count<0) throw new EOFException("旧数据不完整");
                    output.write(buffer,0,count); hash.update(buffer,0,count); remaining-=count;
                }
                if (input.read()!=-1 || Files.getLastModifiedTime(file).toMillis()!=entry.modified)
                    throw new IOException("旧数据已变化，请重新开始迁移");
            }
            output.write(hash.digest());
        }
        output.writeInt(MAGIC); output.flush();
    }
    public static void writeInventory(Snapshot snapshot, OutputStream stream) throws IOException {
        DataOutputStream output=new DataOutputStream(new BufferedOutputStream(stream));
        output.writeInt(MAGIC); output.writeInt(snapshot.entries.size()); output.writeLong(snapshot.bytes);
        for (Entry entry:snapshot.entries) { output.writeUTF(entry.path); output.writeLong(entry.size); }
        output.writeInt(MAGIC); output.flush();
    }
    /** Peak additional bytes for sequential atomic replacements, including the current staging file. */
    public static long requiredSpace(Path root, InputStream stream) throws IOException {
        DataInputStream input=new DataInputStream(new BufferedInputStream(stream));
        if (input.readInt()!=MAGIC) throw new IOException("迁移清单格式不正确");
        int count=input.readInt(); long total=input.readLong();
        if (count<0 || count>MAX_FILES || total<0 || total>MAX_TOTAL) throw new IOException("迁移清单超出限制");
        long read=0, delta=0, peak=0; Set<String> paths=new HashSet<>();
        for (int i=0;i<count;i++) {
            String relative=input.readUTF(); long size=input.readLong(); Path target=resolve(root,relative);
            if (!paths.add(relative) || size<0 || size>total-read) throw new IOException("迁移文件清单不正确");
            long previous=Files.exists(target)?Files.size(target):0;
            peak=Math.max(peak,delta+size); delta+=size-previous; read+=size;
        }
        if (read!=total || input.readInt()!=MAGIC || input.read()!=-1) throw new IOException("迁移清单未完整结束");
        return peak;
    }
    public static long read(Path root, InputStream stream, Progress progress) throws IOException {
        DataInputStream input=new DataInputStream(new BufferedInputStream(stream,131072));
        if (input.readInt()!=MAGIC) throw new IOException("迁移格式不正确");
        int count=input.readInt(); long total=input.readLong();
        if (count<0 || count>MAX_FILES || total<0 || total>MAX_TOTAL) throw new IOException("迁移清单超出限制");
        Path work=root.resolve("files/bybus-migration-work"); Files.createDirectories(work);
        Path pending=work.resolve("incoming.part");
        Set<String> paths=new HashSet<>(); long copied=0;
        byte[] buffer=new byte[131072], expected=new byte[32];
        progress.update(0,total);
        try {
            for (int i=0; i<count; i++) {
                String relative=input.readUTF(); long size=input.readLong();
                Path target=resolve(root,relative);
                if (!paths.add(relative) || size<0 || size>total-copied) throw new IOException("迁移文件清单不正确");
                Files.createDirectories(target.getParent());
                MessageDigest hash=sha256(); long remaining=size;
                try (FileOutputStream output=new FileOutputStream(pending.toFile())) {
                    while (remaining>0) {
                        int length=input.read(buffer,0,(int)Math.min(remaining,buffer.length));
                        if (length<0) throw new EOFException("迁移中断，旧数据仍保留");
                        output.write(buffer,0,length); hash.update(buffer,0,length);
                        copied+=length; remaining-=length; progress.update(copied,total);
                    }
                    input.readFully(expected);
                    if (!MessageDigest.isEqual(expected,hash.digest())) throw new IOException("迁移文件校验失败，旧数据仍保留");
                    output.getFD().sync();
                }
                // Atomic replacement leaves an earlier imported file intact on failure.
                Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }
            if (copied!=total || input.readInt()!=MAGIC || input.read()!=-1) throw new IOException("迁移数据未完整结束");
            progress.update(copied,total);
            return copied;
        } finally { Files.deleteIfExists(pending); }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
