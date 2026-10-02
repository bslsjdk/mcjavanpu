# Native runtime

The native library dynamically loads the verified device-side HTP runtime.

Stage 0 performs provider discovery, backendCreate, deviceCreate, contextCreate, graphCreate and graphFinalize.

No QNN symbols are linked directly.

The next stage will add one deterministic real tensor graph and graphExecute, reusing the verified QNN path from npu_probe.
