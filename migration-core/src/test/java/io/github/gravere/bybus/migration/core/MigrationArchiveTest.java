package io.github.gravere.bybus.migration.core;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import static org.junit.Assert.*;

public class MigrationArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String FILE = "virtual/data/user/0/com.ProjectMoon.LimbusCompany/files/prefs.xml";
    private Path root() throws IOException { return temp.newFolder().toPath(); }
    private void put(Path root, String path, byte[] data) throws IOException {
        Path file=root.resolve(path); Files.createDirectories(file.getParent()); Files.write(file,data);
    }
    private byte[] encode(Path root) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        MigrationArchive.write(root,MigrationArchive.snapshot(root),out); return out.toByteArray();
    }
    private long decode(Path target, byte[] bytes) throws IOException {
        return MigrationArchive.read(target,new ByteArrayInputStream(bytes),(done,total)->assertTrue(done<=total));
    }
    @Test public void roundTripPreservesSourceAndOnlyCopiesPortableData() throws Exception {
        Path source=root(),target=root(); byte[] data=new byte[300_001]; new java.util.Random(41).nextBytes(data);
        put(source,FILE,data);
        put(source,"virtual/storage/emulated/0/Android/data/com.ProjectMoon.LimbusCompany/files/UnityCache/empty",new byte[0]);
        put(source,"virtual/data/app/system/account-list.ini",new byte[]{1,2,3});
        put(source,"files/translation-cache/zh/中文.json",new byte[]{4});
        String gameCache="virtual/storage/emulated/0/Android/data/com.ProjectMoon.LimbusCompany/cache/UnityCache/Shared/bundle/__data";
        put(source,gameCache,new byte[]{5,6});
        put(source,"virtual/data/user/0/com.google.android.gms/cache/skip",new byte[]{9});
        put(source,"virtual/data/app/system/packages.ini",new byte[]{9});
        put(source,"files/translation-cache/redirect-gate.pid",new byte[]{9});
        byte[] archive=encode(source); assertEquals(data.length+6,decode(target,archive));
        assertArrayEquals(data,Files.readAllBytes(source.resolve(FILE)));
        assertArrayEquals(data,Files.readAllBytes(target.resolve(FILE)));
        assertEquals(5,MigrationArchive.snapshot(target).entries.size());
        assertArrayEquals(new byte[]{5,6},Files.readAllBytes(target.resolve(gameCache)));
        assertFalse(Files.exists(target.resolve("virtual/data/app/system/packages.ini")));
        assertFalse(Files.exists(target.resolve("files/translation-cache/redirect-gate.pid")));
    }
    @Test public void interruptedOrCorruptFileKeepsPreviouslyCommittedTargetAndCanRetry() throws Exception {
        Path source=root(),target=root(); byte[] data=new byte[10_000]; Arrays.fill(data,(byte)7); put(source,FILE,data);
        byte[] old={1,2}; put(target,FILE,old); byte[] archive=encode(source);
        assertThrows(IOException.class,()->decode(target,Arrays.copyOf(archive,archive.length-40)));
        assertArrayEquals(old,Files.readAllBytes(target.resolve(FILE)));
        byte[] corrupt=archive.clone(); corrupt[corrupt.length-5]^=1;
        assertThrows(IOException.class,()->decode(target,corrupt));
        assertArrayEquals(old,Files.readAllBytes(target.resolve(FILE)));
        assertFalse(Files.exists(target.resolve("files/bybus-migration-work/incoming.part")));
        assertEquals(data.length,decode(target,archive));
        assertArrayEquals(data,Files.readAllBytes(source.resolve(FILE)));
    }
    @Test public void rejectsTraversalUnsupportedPackagesAndDuplicateEntries() throws Exception {
        for(String path:new String[]{"../escape",FILE+"/../../escape",FILE+":stream",FILE+"\\escape",
                "virtual/data/user/0/other/files/data","virtual/data/user/0/com.google.android.gms/lib/bad"})
            assertFalse(path,MigrationArchive.allowed(path));
        Path source=root(),target=root(); put(source,FILE,new byte[]{1}); byte[] one=encode(source);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x42594d31);out.writeInt(2);out.writeLong(2);
        out.write(one,16,one.length-20);out.write(one,16,one.length-20);out.writeInt(0x42594d31);
        assertThrows(IOException.class,()->decode(target,bytes.toByteArray()));
        assertArrayEquals(new byte[]{1},Files.readAllBytes(target.resolve(FILE)));
    }
    @Test public void rejectsChangedSourceAndMissingTrailer() throws Exception {
        Path source=root(),target=root(); put(source,FILE,new byte[]{1});
        MigrationArchive.Snapshot plan=MigrationArchive.snapshot(source); put(source,FILE,new byte[]{1,2});
        assertThrows(IOException.class,()->MigrationArchive.write(source,plan,new ByteArrayOutputStream()));
        byte[] bytes=encode(source);
        assertThrows(IOException.class,()->decode(target,Arrays.copyOf(bytes,bytes.length-1)));
        assertArrayEquals(new byte[]{1,2},Files.readAllBytes(source.resolve(FILE)));
    }
    @Test public void spacePlanIncludesAtomicReplacementAndDoesNotSubtractUnrelatedFiles() throws Exception {
        Path source=root(),target=root(); put(source,FILE,new byte[20]); put(source,FILE+"z",new byte[15]);
        put(target,FILE,new byte[10]); put(target,FILE+"unrelated",new byte[1000]);
        ByteArrayOutputStream plan=new ByteArrayOutputStream();
        MigrationArchive.writeInventory(MigrationArchive.snapshot(source),plan);
        assertEquals(25,MigrationArchive.requiredSpace(target,new ByteArrayInputStream(plan.toByteArray())));
        decode(target,encode(source));
        assertEquals(20,MigrationArchive.requiredSpace(target,new ByteArrayInputStream(plan.toByteArray())));
    }

    @Test public void runtimeStateIsNotExportedAndOldProviderEntriesCannotOverwriteNewHostState() throws Exception {
        Path source=root(),target=root();
        String state="files/translation-cache/translation-index/runtime-state.properties";
        String pointer="files/translation-cache/translation-index/active-index.path";
        String font="files/translation-cache/runtime-font/font.ttf";
        put(source,state,new byte[]{1}); put(source,pointer,new byte[]{2});
        put(source,font,new byte[]{3}); put(source,FILE,new byte[]{4});
        assertEquals(2,MigrationArchive.snapshot(source).entries.size());
        put(target,state,new byte[]{8}); put(target,pointer,new byte[]{9});
        // This is the transport emitted by the preceding compatible provider.
        java.util.List<MigrationArchive.Entry> entries=new java.util.ArrayList<>();
        for (String path:new String[]{state,pointer,font,FILE}) {
            entries.add(new MigrationArchive.Entry(path,1,Files.getLastModifiedTime(source.resolve(path)).toMillis()));
        }
        ByteArrayOutputStream oldStream=new ByteArrayOutputStream();
        MigrationArchive.write(source,new MigrationArchive.Snapshot(entries,4),oldStream);
        assertEquals(4,decode(target,oldStream.toByteArray()));
        assertArrayEquals(new byte[]{8},Files.readAllBytes(target.resolve(state)));
        assertArrayEquals(new byte[]{9},Files.readAllBytes(target.resolve(pointer)));
        assertArrayEquals(new byte[]{3},Files.readAllBytes(target.resolve(font)));
        assertArrayEquals(new byte[]{4},Files.readAllBytes(target.resolve(FILE)));
    }
}
