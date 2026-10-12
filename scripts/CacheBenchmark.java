import com.example.limbuszhcn.update.*;
import java.nio.file.*;
import java.util.*;

public class CacheBenchmark {
    public static void main(String[] args) throws Exception {
        var request = new ExtractionRequest(Path.of(args[0]), Path.of(args[1]), "2026092102", null);
        // Reuse one benchmark directory; force the app's damaged-manifest rebuild path for the cold run.
        var manifest = Path.of(args[1], "2026092102", "manifest.json");
        if (Files.isRegularFile(manifest)) Files.writeString(manifest, "benchmark-invalidated-manifest");
        var extractor = new TranslationArchiveExtractor(new ExtractionLimits());
        long start = System.nanoTime();
        var first = extractor.extract(request);
        double firstMs = (System.nanoTime()-start)/1e6;
        var warm = new ArrayList<Double>();
        for (int i=0; i<3; i++) {
            start = System.nanoTime();
            var reused = extractor.extract(request);
            warm.add((System.nanoTime()-start)/1e6);
            if (!reused.getFromCache()) throw new AssertionError("Expected verified reuse");
            if (!reused.getManifest().equals(first.getManifest())) throw new AssertionError("Changed manifest");
        }
        System.out.printf(Locale.ROOT, "{\"environment\":\"Windows JDK 17, desktop filesystem\",\"fileCount\":%d,\"coldFromCache\":%b,\"coldMs\":%.3f,\"warmMs\":%s}%n",
            first.getManifest().getEntries().size(), first.getFromCache(), firstMs, warm.toString());
    }
}
