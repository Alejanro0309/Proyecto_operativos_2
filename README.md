# Proyecto_operativos
ÁvilaOS es un simulador académico de un sistema operativo distribuido desarrollado en Java 25 bajo la plataforma NetBeans 25, diseñado para coordinar la ejecución concurrente de un clúster de computadores sincronizados mediante un reloj global. El sistema implementa un modelo de ejecución monohilo donde la unidad de planificación es el propio proceso, asociando de forma unívoca su bloque de control de procesos con el de hilo. Toda la arquitectura está estructurada bajo principios de diseño orientado a objetos y absteniéndose de utilizar el framework de colecciones de Java, por lo que las estructuras de datos para las colas de estados y memoria fueron programadas desde cero.

El núcleo de cada máquina administra de forma independiente la memoria RAM acotada y la planificación de CPU mediante políticas intercambiables en tiempo de ejecución, como FCFS, EDF, Round Robin y prioridades apropiativas. Asimismo, el sistema resuelve la comunicación inter-nodo mediante el modelo del productor-consumidor utilizando semáforos y buffers acotados con penalización por latencia de red en accesos remotos. El estado del clúster, sus métricas de rendimiento y las variaciones de la CPU son monitoreados mediante una interfaz gráfica completamente desacoplada de la lógica del simulador.

# ÁvilaOS — Proyecto 2 (Sistema de archivos, planificación de disco y suspensión de procesos)

El sistema trabaja con una única instancia del computador y un único disco local, ejecutándose cada uno en su propio hilo de Java sincronizado mediante una barrera de `java.util.concurrent.Semaphore` controlada por `GlobalClock`. Toda la solución evita el uso del *Collections Framework* de Java (`ArrayList`, `Queue`, `Stack`, `Vector`, etc.), empleando estructuras de datos propias (`CustomLinkedList`, `CustomQueue`, `Node` y `FileSystemTreeModel`) y manteniendo la interfaz gráfica Swing desacoplada de la lógica del sistema operativo.

## Integrantes
- Sebastián Marval (`SebMarval`)
- Alejandro Marcano (`alejanro0309`)

## Requisitos
- **JDK 25** (Java > 21) y **NetBeans 25** (proyecto Maven).
- Dependencias gestionadas por Maven (`pom.xml`): **Gson 2.11.0** (JSON) y **JFreeChart 1.5.5** (gráficas).

## Cómo ejecutar el Proyecto 2
1. Abrir la carpeta del repositorio en NetBeans (**File → Open Project**).
2. Clic derecho sobre el proyecto → **Clean and Build with Dependencies** (descarga Gson y JFreeChart si aún no están en caché).
3. Ejecutar el proyecto (**Run Project / F6**). La clase principal es `com.avilaos.AvilaOS`, que abre `StartFrame`.
4. En **StartFrame**, cargar obligatoriamente ambos archivos JSON antes de iniciar (el botón **Iniciar simulación** permanece deshabilitado hasta tener los dos válidos):
   - **1. Sistema de archivos y disco:** por ejemplo `config/escenarios_2/sistema_archivos.json`.
   - **2. Procesos y configuración:** por ejemplo `config/escenarios_2/procesos.json` (casos P1–P4) o `config/escenarios_2/procesos_suspension.json` (RAM reducida para observar suspensión).
5. Pulsar **Iniciar simulación** y luego **Iniciar** en el panel global para arrancar el reloj.
   - Desde la interfaz se puede pausar/reanudar, cambiar la duración del ciclo, cambiar la política de CPU y la política de disco en caliente, alternar entre modo **Administrador** y modo **Usuario**, solicitar operaciones CRUD (que se ejecutan como procesos de E/S) y guardar el estado actual del sistema de archivos en JSON (**Guardar sistema de archivos...**).
   - Para pruebas de regresión del Proyecto 1, `StartFrame` incluye el botón **Proyecto 1 (configuración clásica)**.

## Configuraciones incluidas
- **Proyecto 2 (`config/escenarios_2/`):**
  - `sistema_archivos.json`: disco de 200 bloques (cabezal inicial en 50) con la jerarquía y los archivos del enunciado.
  - `procesos.json`: los 8 procesos `FILE_IO` oficiales (casos P1–P4: `95, 180, 34, 119, 11, 123, 62, 64`).
  - `procesos_suspension.json`: carga con RAM de 48 unidades para ejercitar suspensión, *swap* y reactivación.
- **Proyecto 1 (conservadas para regresión):** `config/default.json`, `config/prueba_completa.json`, `config/prueba_completa_2.json` y los 10 escenarios de `config/escenarios/` (documentados en `config/escenarios/README.md`).

## Pruebas y experimentos
Todas las pruebas están en `src/main/java/com/avilaos/tests/` (sin JUnit) y se ejecutan en NetBeans con clic derecho → **Run File (Shift+F6)**:
- **Proyecto 2:** `DiskSchedulingTest`, `DiskTest`, `BlockMapTest`, `FileSystemTest`, `FileSystemTreeModelTest`, `FileIoKernelTest`, `SuspensionTest`, `SystemDiskTest`, `DiskMetricsTest`, `Project2ConfigTest` y `ProcessModelTest`.
- **Regresión Proyecto 1:** `AcceptanceTest`, `KernelTest`, `HardwareTest`, `SchedulingTest`, `SyncTest`, `ProducerConsumerTest`, `RemoteTest`, `MetricsTest`, `ConfigTest`, `GlobalClockTest` y `DataStructuresTest`.
- **Experimentos reproducibles:** `DiskExperimentsRunner` (genera las tablas de `docs/RESULTADOS_EXPERIMENTOS_P2.md`) y `ExperimentsRunner` (Proyecto 1).

## Documentación
- `docs/ARQUITECTURA.md`: paquetes, concurrencia, ciclo del reloj, disco, sistema de archivos y decisiones D1–D7 de suspensión.
- `docs/VALIDACION.md`: resultados de las pruebas del Proyecto 2, regresión del Proyecto 1, auditoría de colecciones y mapa de la GUI.
- `docs/RESULTADOS_EXPERIMENTOS_P2.md`: experimentos A, B y C del Proyecto 2 (políticas de disco, inanición en SSTF y efecto de la suspensión).
- `docs/RESULTADOS_EXPERIMENTOS.md`: resultados experimentales del Proyecto 1.
- `docs/uml/`: diagramas UML de clases y de actividades del ciclo del reloj (fuente PlantUML `.puml` e imagen `.png`).
