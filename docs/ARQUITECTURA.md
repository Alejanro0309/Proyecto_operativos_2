# ÁvilaOS — Contrato de arquitectura (Proyecto 1 y Proyecto 2)

Este documento describe la arquitectura, responsabilidades, sincronización y decisiones de diseño de ÁvilaOS tras la incorporación del disco simulado, el sistema de archivos jerárquico y la suspensión de procesos.

## 1. Paquetes

| Paquete | Responsabilidad |
|---|---|
| `com.avilaos.datastructures` | Estructuras propias sin Collections Framework (`Node`, `CustomLinkedList`, `CustomQueue`). |
| `com.avilaos.enums` | Estados (7 estados), tipos de proceso (`FILE_IO` incluido), políticas de CPU y disco, modos (`CPUMode`, `UserMode`), motivos de bloqueo (`DISK_IO` incluido), operaciones de E/S (`IoOperation`), dirección del cabezal (`HeadDirection`), tipos de bloque (`BlockOwnerType`), archivos (`FileType`, `FilePermission`) y semáforos/micro-instrucciones. |
| `com.avilaos.model` | `ProcessControlBlock` (incluye operación, ruta, dueño, modo solicitante y estado de swap), `ThreadControlBlock` (relación 1:1) y `ProcessIdGenerator` (IDs globales protegidos con `Semaphore`). |
| `com.avilaos.hardware` | `RAM`, `CPU`, `IODevice` (DMA y Red), `Disk` (disco simulado), `ClockListener`, `CompositeClockListener`, `GlobalClock` y `ClockWorker`. El hardware ejecuta órdenes y obedece al reloj sin tomar decisiones de SO. |
| `com.avilaos.disk` | Asignación encadenada (`DiskBlock`, `BlockInfo`, `BlockMap` con zona de archivos y zona de swap), solicitudes (`DiskRequest`, `DiskStatus`), puertos (`DiskPort`, `DiskCompletionListener`) y políticas de planificación (`DiskSchedulingAlgorithm`, `FifoDiskScheduler`, `SstfDiskScheduler`, `ScanDiskScheduler`, `CScanDiskScheduler`, `DiskSchedulerFactory`). |
| `com.avilaos.filesystem` | Árbol jerárquico (`FsNode`, `FileSystem` con validación `check...` y aplicación CRUD) y contratos de solicitud (`FileOperation`, `FileOperationRequester`). |
| `com.avilaos.kernel` | `Kernel` (colas de 7 estados, largo plazo, corto plazo, mediano plazo/suspensión, E/S de archivos y sincronización), `WakeUpRouter` y `EventLogger`. |
| `com.avilaos.scheduling` | Planificación de CPU: `SchedulingAlgorithm` + `FCFSScheduler`, `EDFScheduler`, `RoundRobinScheduler`, `PreemptivePriorityScheduler` y `SchedulerFactory`. |
| `com.avilaos.sync` | Semáforos simulados (`SimSemaphore`) y buffers acotados (`BoundedBuffer`, `BufferStatus`) conservados para compatibilidad y regresión del Proyecto 1. |
| `com.avilaos.system` | `Computer` y `Cluster` (punto único de entrada; en Proyecto 2 conecta 1 computador + `Disk` + `FileSystem` e implementa `FileOperationRequester`). |
| `com.avilaos.config` | Carga, validación y persistencia JSON. Proyecto 2: `FileSystemConfig`, `FileSystemLoader` (carga/guardado con soporte de cadenas fragmentadas `chain`), `FileSystemValidator`, `WorkloadConfig`, `WorkloadLoader`, `WorkloadValidator` y `SystemFactory`. Proyecto 1: `SimulationConfig`, `ConfigLoader`, `ConfigValidator` y `ClusterFactory`. |
| `com.avilaos.metrics` | `MetricsCalculator`, `MetricsCollector`, `ComputerMetrics`, `GlobalMetrics`, `BufferMetrics`, `DiskMetrics` y `DiskMetricsCollector`. |
| `com.avilaos.gui` | Interfaz Swing desacoplada (solo observa snapshots y envía órdenes): `StartFrame`, `MainFrame`, `GlobalPanel`, `ComputerPanel`, `FileSystemPanel`, `FileSystemTreeModel`, `AllocationTablePanel`, `DiskPanel`, `DiskQueuePanel`, `DiskMetricsPanel`, `HeadDisplacementChartPanel`, `FileOperationDialog`, `ModePanel`, `UserSession`, `FileColors`, `MetricsPanel`, `UtilizationChartPanel` y `GuiEventLogger`. |

## 2. Dos tipos de semáforo y orden de bloqueo

| | Semáforo simulado (`SimSemaphore`) | `java.util.concurrent.Semaphore` |
|---|---|---|
| Qué es | Recurso del SO simulado: contador + cola FIFO propia de PCB | Mecanismo real de concurrencia de Java |
| Bloquea a | Procesos simulados (pasan a `BLOCKED` y sueltan la CPU) | Hilos reales de Java |
| Uso | `s`, `n`, `e` de buffers (Proyecto 1) | Barrera del reloj (`tickStart`/`tickDone`), locks de `Kernel`, `Disk`, `BlockMap`, `FileSystem`, `BoundedBuffer`, colectores de métricas y buzones |

No se usa `synchronized`: toda la exclusión mutua entre hilos reales se realiza con `java.util.concurrent.Semaphore`. Para prevenir interbloqueos se respeta un **orden fijo de adquisición de locks** y comunicación asíncrona por buzones:
- Entre kernel y buffers/buzones: `Kernel.lock` → `BoundedBuffer.lock` → `Kernel.mailboxLock`. Ningún kernel toma el lock de otro kernel.
- Entre sistema de archivos y mapa de bloques: `FileSystem.lock` → `BlockMap.lock` (nunca al revés).
- Entre disco y kernel: `Disk.onTick()` invoca `DiskCompletionListener.onRequestCompleted(...)` **fuera de `Disk.lock`**; el kernel solo encola el aviso en `diskMailbox` bajo `mailboxLock` y lo procesa en el ciclo siguiente.

## 3. Hilos, reloj global y determinismo

- Todos los componentes que avanzan con el reloj implementan `ClockListener`: `Computer`, `Disk`, `CPU`, `IODevice`, `MetricsCollector` y `DiskMetricsCollector` (agrupados mediante `CompositeClockListener` como observador de fin de tick).
- En el Proyecto 2, `GlobalClock` coordina **dos hilos `ClockWorker`**: `Computer-0` y `Disk`. En cada tick:
  1. `GlobalClock` incrementa el ciclo y libera `tickStart` para todos los workers.
  2. Cada worker ejecuta exactamente un ciclo (`onTick(cycle)`) y libera `tickDone`.
  3. `GlobalClock` espera `tickDone.acquire(N)` (barrera), notifica a los colectores de métricas con el sistema en reposo y duerme `cycleDurationMs`.
- **Determinismo con hilos reales:**
  - En `Disk`, toda solicitud llegada en el ciclo `c` entra primero a `arrivals` y solo pasa a competir en `queue` a partir del ciclo `c + 1`.
  - En `Kernel`, todo aviso recibido en `wakeUpMailbox` o `diskMailbox` en el ciclo `c` se procesa en el paso 0 del ciclo `c + 1`.
  - De este modo, el resultado no depende del orden en que corran los hilos `Computer-0` y `Disk` dentro del mismo tick, coincidiendo exactamente con las corridas de ticks manuales.

## 4. Ciclo de un computador (`Kernel.runCycle`)

0. **Buzones del ciclo anterior:**
   - `0a. processWakeUps()`: despertares de `semSignal` (`BLOCKED → READY` o `BLOCKED_SUSPENDED → READY_SUSPENDED`).
   - `0b. processDiskCompletions()`: completaciones del disco (`SUSPEND` libera RAM, `REACTIVATE` libera swap y pasa a `READY`, operaciones CRUD aplican el cambio en `FileSystem` y pasan el proceso a `READY`, `READY_SUSPENDED` o `TERMINATED`).
1. **Dispositivos (`DMA` y `Red`):** reciben `onTick()`; los procesos que culminan pasan a `READY` (o `READY_SUSPENDED` si estaban suspendidos).
2. **Admisión (largo y mediano plazo):** admite `READY_SUSPENDED` (prioridad sobre nuevos) y `NEW` mientras haya RAM; si el primero de la fila no cabe, invoca al planificador a mediano plazo (`makeRoomFor`).
3. **Planificación de corto plazo:** la política activa decide si expropia y, si la CPU está libre, despacha el siguiente proceso de `readyQueue`.
4. **Ejecución:** 1 instrucción = 1 ciclo (usuario, llamada al sistema de buffer o generación de solicitud al disco en modo SO).
5. **Post-ejecución:** mueve a `blockedList` si se bloqueó, termina y libera RAM si acabó sus instrucciones, o inicia servicio DMA si pide E/S temporizada.
6. **Deadlines:** descuenta 1 en todos los estados vivos (incluidos `NEW`, `READY_SUSPENDED` y `BLOCKED_SUSPENDED`); al llegar a 0 cancela solicitudes pendientes, libera recursos (RAM, swap, semáforos) y pasa a `TERMINATED`.
7. **Contabilización del ciclo:** acumula esperas en `READY` y `BLOCKED`.

## 5. Disco simulado y planificación de E/S

- **Mapa de bloques (`BlockMap`):** dividido en dos zonas independientes:
  - Zona de archivos: bloques `0 .. fileAreaBlocks - 1` (configurable entre `100` y `500` bloques).
  - Zona de intercambio (*swap*): bloques `fileAreaBlocks .. fileAreaBlocks + swapAreaBlocks - 1`.
- **Asignación encadenada:** cada `DiskBlock` guarda `BlockOwnerType` (`FREE`, `FILE`, `SWAP`), el nombre del dueño y el puntero `next` (`-1` en el último bloque). Permite asignar bloques no contiguos en huecos libres (`allocateFile`), bloques contiguos desde JSON (`assign`) o cadenas fragmentadas restauradas desde un estado guardado (`assignChain`).
- **Ciclo de trabajo del disco (`Disk.onTick`):**
  1. Admite en `queue` las solicitudes de `arrivals` con `arrivalCycle < cycle`.
  2. Si el disco está libre, selecciona la siguiente solicitud mediante `DiskSchedulingAlgorithm.selectNextIndex(queue, head, direction)`, calcula la distancia con `travelDistance(...)` y registra el evento en el log.
  3. En cada ciclo ejecuta un paso de búsqueda (`headSpeed` posiciones/ciclo, valor configurable opcional `headSpeed`, por omisión `10`) o 1 ciclo de transferencia por bloque (`blockCount`).
  4. Al completar búsqueda y transferencia, incrementa métricas (`served`, `servedPerPolicy`) y notifica a `DiskCompletionListener` fuera del lock.
- **Extensibilidad de políticas:** `DiskSchedulingAlgorithm` desacopla el algoritmo de `Disk`. Implementa `FIFO`, `SSTF`, `SCAN` (recorre hasta el extremo antes de regresar) y `C_SCAN` (salto de `totalBlocks - 1` a `0` sin contabilizar el regreso como desplazamiento). Agregar una política nueva (como `LOOK`) solo requiere implementar la interfaz, añadir el valor en `DiskPolicy` y registrarlo en `DiskSchedulerFactory`. El cambio de política en caliente aplica a partir de la próxima selección sin interrumpir la solicitud en curso.

## 6. Sistema de archivos, modos de usuario y procesos de E/S

- **Estructura:** árbol jerárquico de `FsNode` (cada directorio almacena sus hijos en una `CustomLinkedList<FsNode>`). La ruta se construye dinámicamente desde la raíz, por lo que renombrar un directorio actualiza automáticamente las rutas de todo su subárbol y rotula sus bloques en `BlockMap`.
- **Procesos `FILE_IO`:** la GUI nunca modifica `FileSystem` ni `BlockMap` directamente. Al confirmar una acción en `FileOperationDialog`, envía un `FileOperation` a `Cluster.requestFileOperation(...)`, que crea un PCB `FILE_IO` (con operación, ruta, dueño y modo solicitante). Su primera instrucción se ejecuta en **modo SO** (`CPU.executeKernelInstruction()`), valida permisos/estado, encola una `DiskRequest` y bloquea el proceso con motivo `DISK_IO`. El cambio en `FileSystem` se aplica recién cuando el disco completa la solicitud.
- **Modos de usuario (`UserMode`):**
  - `ADMINISTRATOR`: permiso total para crear archivos o directorios (pudiendo asignar el dueño al crear), leer, renombrar (`UPDATE`) y eliminar archivos o directorios recursivamente.
  - `USER`: puede leer archivos propios o `PUBLIC`, crear archivos únicamente dentro de directorios de su propiedad y eliminar solo archivos propios; no puede crear ni eliminar directorios, ni renombrar, ni acceder a archivos `PRIVATE` ajenos. Los rechazos muestran el motivo exacto sin interrumpir la simulación.
- **Persistencia JSON:** `StartFrame` exige cargar ambos JSON (sistema de archivos y carga de procesos) antes de arrancar. `FileSystemLoader.save(...)` exporta `diskConfig` (tamaño y posición actual del cabezal) y todas las entradas del árbol incluyendo el arreglo opcional `chain` para preservar fielmente archivos fragmentados al recargar.

## 7. Modelo de siete estados y planificador a mediano plazo (decisiones D1–D7)

El modelo se amplía con `READY_SUSPENDED` y `BLOCKED_SUSPENDED` (más sus colas `readySuspendedList` y `blockedSuspendedList` en `Kernel`). Una unidad de RAM equivale a un bloque en el área de *swap*. El comportamiento del planificador a mediano plazo queda regido por siete decisiones verificadas en `SuspensionTest`:

- **D1 — Criterio de selección de víctima:** se suspende al proceso `BLOCKED` de **menor prioridad** (mayor valor numérico); en caso de empate, se elige al que lleva más tiempo bloqueado (primero en `blockedList`). Justificación: un proceso bloqueado no puede usar la CPU y, entre ellos, el menos prioritario es el que menos conviene retener en RAM.
- **D2 — Admisión sin suspensiones inútiles y reserva para el objetivo:** el planificador solo intenta abrir espacio para el primero de la fila (`nextInLine()`, uno a la vez mediante `roomTarget`). Si ni suspendiendo a todos los procesos bloqueados alcanza para cubrir la memoria faltante (`missing`), no se suspende a nadie para evitar tráfico de disco sin beneficio. Lo que ya está escribiéndose al swap (`memoryBeingFreed`) se descuenta para no suspender de más.
- **D3 — Momento de liberar y reservar RAM:** al suspender, la RAM de la víctima **no se libera al decidir**, sino cuando el disco confirma que terminó de escribir su imagen al swap (`finishSwapOut`), garantizando coherencia de la imagen. Al reactivar, la RAM se reserva en el instante de la decisión (`startSwapIn`) para asegurar que el proceso tendrá dónde cargarse al volver del disco.
- **D4 — Prioridad de `READY_SUSPENDED` sobre `NEW`:** cuando se libera RAM, se reactivan primero los procesos `READY_SUSPENDED` (en orden FIFO) antes de admitir procesos `NEW`, evitando la inanición de procesos que ya habían sido admitidos al sistema.
- **D5 — Operaciones de swap en la cola del disco:** tanto `SUSPEND` como `REACTIVATE` generan solicitudes reales en la única cola de E/S del disco (posición de entrada al swap `fileAreaBlocks - 1` y transferencia igual a `memoryRequired` bloques).
- **D6 — Swap lleno:** si `BlockMap.allocateSwap(...)` devuelve `-1`, la suspensión se cancela limpiamente, se registra `"Swap lleno"` en el log y el proceso solicitante espera a que se libere RAM de forma natural.
- **D7 — Deadlines en el swap:** el contador de deadline continúa descontando mientras el proceso está en `READY_SUSPENDED` o `BLOCKED_SUSPENDED`. Si vence en el swap, se cancelan sus solicitudes pendientes en el disco, se liberan sus bloques de swap (y su RAM si estaba en tránsito) y pasa a `TERMINATED`.

## 8. Convenciones

- Código, clases, enums, interfaces y métodos en inglés; comentarios y documentación en español.
- Flujo Git: ramas `feat/*`, `fix/*`, `docs/*` desde `develop`; integración mediante Pull Requests comentados y PR final `develop → main`.
- Prefijos de commit: `feat:`, `fix:`, `test:`, `docs:`, `chore:`.
