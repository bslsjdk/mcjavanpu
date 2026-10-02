# mcjavanpu

Minecraft 26.3 Fabric client bridge for the separate Android mcnpu service.

## Architecture
- mcjavanpu runs inside Minecraft and never loads QNN or HTP native libraries.
- mcnpu is the persistent Android service that owns QNN/HTP V73.
- IPC uses Android abstract local socket mcnpu_ipc_v1.
- Commands: PING, STATUS, CAPABILITIES, SMOKE, EXEC_ADD, QUIT.
- Successful execution replies begin with OK HTP_GRAPH_EXECUTE.

The Fabric mod uses Android LocalSocket only for IPC. This repository deliberately contains no in-process QNN runtime.