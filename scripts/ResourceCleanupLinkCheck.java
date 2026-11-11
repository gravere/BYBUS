import com.example.limbuszhcn.gamefs.DownloadedResourceCleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runs the compiled production cleaner on a filesystem that supports symbolic links. */
public final class ResourceCleanupLinkCheck {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("bybus-cleaner-");
        Path assets = Files.createDirectories(root.resolve("files/Assets"));
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path account = Files.writeString(outside.resolve("account.xml"), "keep");
        Path localize = assets.resolve("Resources_moved/Localize");
        Files.createSymbolicLink(assets.resolve("link"), outside);
        DownloadedResourceCleaner.INSTANCE.clear(localize, Map.of("assets", assets));
        if (!Files.readString(account).equals("keep")) throw new AssertionError("Followed a nested link");
        Path resource = Files.writeString(assets.resolve("resource.bin"), "keep");
        Path cache = Files.createDirectories(root.resolve("cache")).resolve("UnityCache");
        Files.createSymbolicLink(cache, outside);
        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put("assets", assets);
        roots.put("internal-unity-cache", cache.resolve("Shared"));
        boolean rejected = false;
        try { DownloadedResourceCleaner.INSTANCE.clear(localize, roots); }
        catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected || !Files.exists(resource) || !Files.readString(account).equals("keep"))
            throw new AssertionError("Did not reject a linked parent before deletion");
        System.out.println("PASS: nested link target preserved; linked parent rejected before deletion");
    }
}
