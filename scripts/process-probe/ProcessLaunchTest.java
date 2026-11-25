import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class ProcessLaunchTest {
    private static native void configure(boolean locked);
    private static native int forkCallbacks();
    private static native void finish();
    private static ProcessBuilder builder(String... args) {
        ProcessBuilder b = new ProcessBuilder(args);
        b.environment().remove("LD_PRELOAD");
        b.environment().put("BYBUS_EXEC_SENTINEL", "kept");
        return b;
    }
    private static String read(InputStream stream) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[256];
        for(int n;(n=stream.read(buf))!=-1;) {out.write(buf,0,n);if(out.size()>2048)throw new AssertionError("Too much output");}
        return out.toString("UTF-8");
    }
    private static void success() throws Exception {
        Process p=builder("/bybus-probe/sh", "-c", "read value; printf '%s:%s:' \"$value\" \"$BYBUS_EXEC_SENTINEL\"; pwd; printf error >&2; exit 7")
            .directory(new File("/bybus-probe/work")).start();
        p.getOutputStream().write("input\n".getBytes(StandardCharsets.UTF_8));p.getOutputStream().close();
        if(!p.waitFor(5,TimeUnit.SECONDS)) {p.destroyForcibly();throw new AssertionError("Child did not exit");}
        if(p.exitValue()!=7 || !read(p.getInputStream()).equals("input:kept:/data/local/tmp\n") || !read(p.getErrorStream()).equals("error"))throw new AssertionError("Changed pipes/environment/path/status");
    }
    private static void failure(ProcessBuilder b, String code) throws Exception {
        try {Process p=b.start();p.destroyForcibly();throw new AssertionError("Expected real failure");}
        catch(IOException expected) {if(!expected.getMessage().contains(code))throw expected;}
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]); boolean locked=args[1].equals("locked");configure(locked);
        ExecutorService jobs=Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> work=new ArrayList<>();
            for(int i=0;i<3;i++)work.add(jobs.submit(()->{try{success();}catch(Exception e){throw new RuntimeException(e);}}));
            for(Future<?> f:work)f.get(10,TimeUnit.SECONDS);
            failure(builder("/bybus-probe/missing"),"error=2");
            failure(builder("/bybus-probe/denied"),"error=13");
            failure(builder("/bybus-probe/sh","-c","true").directory(new File("/bybus-probe/missing-dir")),"error=2");
            if(args.length>2 && args[2].equals("no-callbacks") && forkCallbacks()!=0)throw new AssertionError("Platform vfork invoked fork callbacks");
            System.out.println("ProcessBuilder passed: concurrent exec, stdin/stdout/stderr, cwd, environment, true exit status, ENOENT/EACCES; atfork="+forkCallbacks());
        } finally {jobs.shutdownNow();finish();}
    }
}
