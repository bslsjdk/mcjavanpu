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

    /** Deterministic matmul on the persistent HTP service; reply carries cpu_us and speedup. */
    public static String matMul(int m,int k,int n){
        if(!isAvailable()) return "ERR MCNPU_OFFLINE "+getDeviceInfo();
        return NpuServiceClient.matMul(m,k,n);
    }

    /** INT8 quantized matmul - the path that actually runs at HTP speed. */
    public static String matMulInt8(int m,int k,int n){
        if(!isAvailable()) return "ERR MCNPU_OFFLINE "+getDeviceInfo();
        return NpuServiceClient.matMulInt8(m,k,n);
    }

    /** Result of a real-data int8 matmul: dequantize with scaleC. */
    public record MatMulResult(float scaleC, byte[] c, long us, String error){
        public boolean ok(){ return error == null; }
    }

    /**
     * Real data path: caller-side int8 tensors (normalized to [-1,1]) go straight
     * to the HTP and the raw int8 result comes back. Use scaleC to dequantize.
     */
    public static MatMulResult submitMatMulInt8(byte[] a,byte[] b,int m,int k,int n){
        if(!isAvailable()) return new MatMulResult(0,null,0,"MCNPU_OFFLINE "+getDeviceInfo());
        return NpuServiceClient.submitBinMatMul8(a,b,m,k,n);
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
