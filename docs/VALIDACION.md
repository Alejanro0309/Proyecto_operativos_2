# ÁvilaOS — Validación final (Proyecto 2 y regresión del Proyecto 1)

Fecha: 24/11/2026 · Rama: `develop` · Java 25 / NetBeans 25 (Maven)

## 1. Resumen general

| Bloque de validación | Clases ejecutadas | Resultado |
|---|---|---|
| Pruebas unitarias e integración del Proyecto 2 | 11 clases (`Disk*`, `BlockMap*`, `FileSystem*`, `FileIoKernel*`, `Suspension*`, `SystemDisk*`, `Project2Config*`, `ProcessModel*`) | ✅ Fallidas: 0 |
| Regresión completa del Proyecto 1 | 11 clases (`AcceptanceTest` T01–T10 + 10 pruebas unitarias) | ✅ Fallidas: 0 |
| Experimentos reproducibles P2 (`DiskExperimentsRunner`) | Experimentos A, B y C | ✅ Ejecutados y registrados en `docs/RESULTADOS_EXPERIMENTOS_P2.md` |
| Interfaz gráfica (GUI en NetBeans) | `StartFrame`, `MainFrame`, `sistema_archivos.json`, `procesos.json`, `procesos_suspension.json` | ✅ Verificada sin excepciones |
| Auditoría de colecciones prohibidas (`src/`) | Todo el árbol de código fuente | ✅ Solo `java.util.concurrent.Semaphore` |

Cómo reproducir: en NetBeans, clic derecho sobre cada clase de `com.avilaos.tests` → **Run File (Shift+F6)** (requiere ejecutar desde la raíz del proyecto para las pruebas que leen `config/`).

## 2. Pruebas del Proyecto 2

| Clase | Alcance verificado | Resultado |
|---|---|---|
| `DiskSchedulingTest` | Anexo oficial (`518, 208, 332, 192`) y casos P1–P4 (`644, 236, 337, 183`), fábrica y casos borde | ✅ Fallidas: 0 |
| `DiskTest` | Reloj manual en `Disk`, órdenes P1–P4, tiempos de espera (`FIFO 37.5`), regla de llegada al ciclo siguiente, multi-bloque, velocidad del cabezal y cambio de política en caliente | ✅ Fallidas: 0 |
| `BlockMapTest` | Asignación encadenada, liberación y reutilización de huecos, fragmentación, área de swap independiente y disco oficial de 200 bloques | ✅ Fallidas: 0 |
| `FileSystemTest` | Jerarquía, `CREATE`/`READ`/`UPDATE`/`DELETE`, borrado recursivo de directorios, renombrado y rotulado de bloques, permisos `ADMINISTRATOR` vs `USER` y manejo de errores | ✅ Fallidas: 0 |
| `FileSystemTreeModelTest` | Modelo propio del `JTree` sin `DefaultMutableTreeNode`, jerarquía, listeners y firma de cambios | ✅ Fallidas: 0 |
| `FileIoKernelTest` | Procesos `FILE_IO` conectados al kernel: caso P1 FIFO (`95 180 34 119 11 123 62 64`), CPU útil durante espera de disco, rechazo por permisos antes del disco, `CREATE` con disco lleno/libre y cancelación por deadline | ✅ Fallidas: 0 |
| `SuspensionTest` | Decisiones D1–D7, invariantes de RAM/swap/colas en cada ciclo, carga de estrés (30 procesos) y determinismo con hilos reales (5 corridas) | ✅ Fallidas: 0 |
| `SystemDiskTest` | Integración con hilos reales (`Computer-0` y `Disk`, verificados con `Thread.enumerate`), pausa/reanudación del disco y determinismo exacto frente a ticks manuales | ✅ Fallidas: 0 |
| `DiskMetricsTest` | Desplazamiento total, solicitudes atendidas por política, espera promedio, utilización e historial circular para la gráfica con cambio de política | ✅ Fallidas: 0 |
| `Project2ConfigTest` | Carga y validación de ambos JSON, guardado y recarga ida y vuelta con archivos fragmentados (`chain`), `quantum: 0` fuera de RR, `C-SCAN`/`C_SCAN`, rechazo de archivos inválidos e integración real con `config/escenarios_2/` | ✅ Fallidas: 0 |
| `ProcessModelTest` | PCB/TCB, IDs únicos concurrentes, deadlines, `IO_BOUND` y atributos/validaciones de `FILE_IO` | ✅ Fallidas: 0 |

## 3. Regresión del Proyecto 1

| Clase | Componente | Resultado |
|---|---|---|
| `AcceptanceTest` | Escenarios integrados T01–T10 sobre `Cluster` | ✅ 10/10 aprobados (Fallidas: 0) |
| `DataStructuresTest` | `CustomLinkedList`, `CustomQueue` | ✅ Fallidas: 0 |
| `HardwareTest` | `RAM`, `CPU`, `IODevice` (`ClockListener`) | ✅ Fallidas: 0 |
| `KernelTest` | Colas, admisión por RAM, DMA y deadlines | ✅ Fallidas: 0 |
| `SchedulingTest` | Prueba automatizada (sin `Scanner`) de FCFS, RR, EDF y Prioridades | ✅ Fallidas: 0 |
| `GlobalClockTest` | Reloj global, barrera de semáforos, pausa y velocidad | ✅ Fallidas: 0 |
| `SyncTest` | `SimSemaphore` y `BoundedBuffer` (incluye concurrencia con 2 hilos) | ✅ Fallidas: 0 |
| `ProducerConsumerTest` | Micro-programa, contención en mutex con RR, liberación al vencer deadline | ✅ Fallidas: 0 |
| `RemoteTest` | `Cluster`, latencia de red, despertares entre computadores y determinismo | ✅ Fallidas: 0 |
| `MetricsTest` | Fórmulas (Jain, porcentajes) y colector de métricas | ✅ Fallidas: 0 |
| `ConfigTest` | Persistencia y validación JSON del Proyecto 1 (`ClusterFactory`) | ✅ Fallidas: 0 |

## 4. Auditoría de restricciones técnicas

- **Colecciones prohibidas de Java:** búsqueda global sobre `src/` de `java.util.`, `ArrayList`, `Queue`, `Stack`, `Vector`, `HashMap` y `List<`. Resultado: únicamente `java.util.concurrent.Semaphore` (exigido por el enunciado). Se eliminaron `CustomStack`/`StackNode`, `SchedulingTest` dejó de usar `java.util.Scanner` y `SystemDiskTest` reemplazó `Thread.getAllStackTraces().keySet()` por `Thread.enumerate(Thread[])`.
- **Entorno:** compilación y ejecución verificadas con Java 25 en NetBeans 25 (`pom.xml` con `maven.compiler.release = 25`).
- **Desacoplamiento de la GUI:** las ventanas y paneles únicamente leen copias inmutables (`snapshot()`) y delegan acciones mediante `Cluster`, `Disk.setPolicy(...)` y `FileOperationRequester`.

## 5. Requisitos de la GUI del Proyecto 2 → dónde se observan

| Requisito del enunciado | Ubicación en ÁvilaOS |
|---|---|
| Carga obligatoria de los dos JSON antes de iniciar | `StartFrame` (el botón **Iniciar simulación** se habilita solo tras validar ambos JSON) |
| Estructura jerárquica de directorios/archivos e información del elemento | Pestaña **Archivos** → `FileSystemPanel` (`JTree` con `FileSystemTreeModel` propio + panel de detalles) |
| Tabla de asignación con colores actualizados en tiempo real | Pestaña **Archivos** → `AllocationTablePanel` (`JTable` sincronizada con `FileColors`) |
| Mapa de bloques del disco, área de swap, cabezal y dirección | Pestaña **Disco** → `DiskPanel` (mapa de bloques con tooltips + franja de swap + borde rojo del cabezal) |
| Cola de E/S del disco con operación y selector de política en ejecución | Pestaña **Disco** → `DiskQueuePanel` (`FIFO`, `SSTF`, `SCAN`, `C_SCAN` en caliente) |
| Colas de 7 estados (`Listo/Suspendido` y `Bloqueado/Suspendido` con motivo) | Pestaña **Computador 0** → `ComputerPanel` (6 tablas de colas + CPU + RAM + política de CPU) |
| Operaciones CRUD solo por procesos de E/S y selector de modo de usuario | Panel global (`GlobalPanel`) → **Operación de archivo...** (`FileOperationDialog`) y `ModePanel` (`ADMINISTRATOR` / `USER`) |
| Guardar estado del sistema de archivos en JSON | Panel global (`GlobalPanel`) → **Guardar sistema de archivos...** (`FileSystemLoader.save`) |
| Log de eventos con decisiones del disco y del planificador a mediano plazo | Panel global (`GlobalPanel`) → **Log de eventos** (`GuiEventLogger`) |
| Métricas del disco y de suspensión | Pestaña **Métricas del disco** → `DiskMetricsPanel` |
| Gráfica de desplazamiento acumulado del cabezal en el tiempo | Pestaña **Gráfica del cabezal** → `HeadDisplacementChartPanel` (JFreeChart, una serie por política) |
| Métricas y gráfica de CPU del Proyecto 1 | Pestañas **Métricas** y **Gráfica de utilización** |