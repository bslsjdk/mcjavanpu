package bslsjdk.mcjavanpu;

/** Minecraft-side client facade. Actual QNN/HTP execution lives in persistent MCNPU. */
public final class NpuRuntime {
    private static volatile boolean initialized;
    private static volatile boolean available;
    private static volatile String loadError = "service not checked";
    private static volatile String diagnostics = "";

    private NpuRuntime() {}

    public static synchronized void init() {
        try {
            HtpBackend.getInstance().initialize();
        } catch (Throwable error) {
            initialized = true;
            available = false;
            loadError = "INIT_EXCEPTION " + error.getClass().getSimpleName() + ": " + error.getMessage();
            diagnostics = "MCNPU_INIT_EXCEPTION " + loadError;
            System.err.println("[MCJavaNPU] " + diagnostics);
        }
    }

    static synchronized boolean initInternal() {
        String ping = NpuServiceClient.request("PING");
        if (!ping.startsWith("PONG MCNPU/")) {
            available=false; initialized=true; loadError=ping;
            diagnostics="MCNPU_SERVICE_OFFLINE " + ping;
            System.err.println("[MCJavaNPU] " + diagnostics);
            return false;
        }
        String status=NpuServiceClient.status();
        available=status.startsWith("QNN HTP ready");
        initialized=true; loadError=available?"":status;
        diagnostics="PING="+ping+"\nSTATUS="+status+"\nCAPABILITIES="+NpuServiceClient.capabilities();
        System.out.println("[MCJavaNPU] persistent MCNPU service connected");
        System.out.println("[MCJavaNPU] "+diagnostics.replace("\n"," | "));
        return available;
    }

    public static boolean isInitialized(){return initialized;}
    public static boolean isAvailable(){return available && NpuServiceClient.isAvailable();}
    public static String getLoadError(){return loadError;}
    public static String getDiagnostics(){return diagnostics;}

    public static String getDeviceInfo(){
        String status=NpuServiceClient.status();
        if(!status.startsWith("QNN HTP ready")) loadError=status;
        return status;
    }

    public static String getLogPath(){
        return "MCNPU service log: query from MCNPU diagnostic UI";
    }

    public static TestResult test(){return testInternal();}

    static TestResult testInternal(){
        if(!isAvailable()) return TestResult.failure("UNAVAILABLE",getDeviceInfo());
        try{
            String result=NpuServiceClient.smoke();
            boolean ok=result.startsWith("OK HTP_GRAPH_EXECUTE");
            return ok?TestResult.success("PASS",result):TestResult.failure("FAIL",result);
        }catch(Throwable error){return TestResult.failure("ERROR",error.toString());}
    }

    public static TestResult benchmark(){
        if(!isAvailable()) return TestResult.failure("UNAVAILABLE",getDeviceInfo());
        long t0=System.nanoTime(); int pass=0; String last="";
        for(int i=0;i<8;i++){
            last=NpuServiceClient.smoke();
            if(last.startsWith("OK HTP_GRAPH_EXECUTE")) pass++;
        }
        double ms=(System.nanoTime()-t0)/1_000_000.0;
        return pass==8
            ?TestResult.success("READY","8/8 HTP executions; wall_ms="+ms+"; last="+last)
            :TestResult.failure("FAIL",pass+"/8 HTP executions; last="+last);
    }

    /** Submit a real vector ADD task to the persistent HTP service. */
    public static String add(float[] a,float[] b){
        if(!isAvailable()) return "ERR MCNPU_OFFLINE "+getDeviceInfo();
        return NpuServiceClient.add(a,b);
    }

    public static synchronized void shutdown(){HtpBackend.getInstance().close();}

    static synchronized void shutdownInternal(){
        // Minecraft must never shut down the independent MCNPU service.
        initialized=false; available=false;
    }

    public record TestResult(boolean success,String name,String detail){
        static TestResult success(String name,String detail){return new TestResult(true,name,detail);}
        static TestResult failure(String name,String detail){return new TestResult(false,name,detail);}
    }
}
