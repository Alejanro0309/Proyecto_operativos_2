/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.avilaos.system;

import com.avilaos.datastructures.CustomLinkedList;
import com.avilaos.enums.IoOperation;
import com.avilaos.enums.SchedulingPolicy;
import com.avilaos.filesystem.FileOperation;
import com.avilaos.filesystem.FileOperationRequester;
import com.avilaos.filesystem.FileSystem;
import com.avilaos.hardware.ClockListener;
import com.avilaos.hardware.Disk;
import com.avilaos.hardware.GlobalClock;
import com.avilaos.kernel.EventLogger;
import com.avilaos.kernel.Kernel;
import com.avilaos.model.ProcessControlBlock;
import com.avilaos.sync.BoundedBuffer;
import java.util.concurrent.Semaphore;

/**
 * El sistema distribuido completo: los computadores, el reloj global, la red
 * (latencia) y el registro de buffers compartidos.
 *
 * Es el único punto de entrada que necesitan la GUI y la configuración:
 *  - crear el clúster (n computadores, RAM, política, quantum, ciclo, latencia),
 *  - crear buffers y procesos (en un computador elegido o automático),
 *  - controlar el reloj (iniciar, pausar, reanudar, detener, velocidad).
 *
 * Red: cuando un semSignal despierta a un proceso de otro computador, el
 * clúster lo entrega al buzón del kernel correcto (WakeUpRouter).
 * No hay migración: un proceso vive siempre en el computador donde se creó.
 *
 * Proyecto 2: también es el FileOperationRequester: la interfaz le pide una operación
 * sobre un archivo y el clúster crea el proceso de E/S (la interfaz nunca toca el disco).
 *
 * @author SebMarval
 */
public final class Cluster implements FileOperationRequester {

    public static final int MIN_COMPUTERS = 2;

    private final Computer[] computers;
    private final GlobalClock clock;
    private final EventLogger logger;
    private final CustomLinkedList<BoundedBuffer> buffers = new CustomLinkedList<>();
    private final Semaphore buffersLock = new Semaphore(1);   // la GUI puede crear buffers en ejecución
    private int networkLatency;

    // Proyecto 2: el disco y el sistema de archivos (null en el clúster del Proyecto 1)
    private final Disk disk;
    private final FileSystem fileSystem;

    /**
     * @param ramCapacities   RAM de cada computador (su longitud = cantidad de computadores)
     * @param policy          política inicial de todos los computadores
     * @param quantum         quantum inicial (Round Robin)
     * @param cycleDurationMs duración del ciclo del reloj en milisegundos
     * @param networkLatency  ciclos que bloquea cada acceso a un buffer remoto
     */
    public Cluster(int[] ramCapacities, SchedulingPolicy policy, int quantum,
            int cycleDurationMs, int networkLatency, EventLogger logger) {
        if (ramCapacities == null || ramCapacities.length < MIN_COMPUTERS) {
            throw new IllegalArgumentException("El clúster necesita al menos " + MIN_COMPUTERS + " computadores");
        }
        this.logger = (logger == null) ? EventLogger.SILENT : logger;
        this.computers = new Computer[ramCapacities.length];
        for (int i = 0; i < ramCapacities.length; i++) {
            computers[i] = new Computer(i, ramCapacities[i], policy, quantum, this.logger);
            computers[i].getKernel().setWakeUpRouter(this::routeWakeUp);
        }
        setNetworkLatency(networkLatency);
        this.disk = null;
        this.fileSystem = null;
        this.clock = new GlobalClock(computers, cycleDurationMs);
    }

    /**
     * Sistema del Proyecto 2: UN computador y un disco con su sistema de archivos.
     *
     * El reloj tiene dos componentes, cada uno en su hilo: el computador y el
     * disco. Ninguno empieza el ciclo k+1 hasta que los dos terminaron el k.
     * El kernel le pide trabajo al disco por su puerto (DiskPort) y el disco
     * le avisa al kernel cuando termina (DiskCompletionListener).
     *
     * @param ramCapacity     RAM del computador
     * @param policy          política inicial de planificación de CPU
     * @param quantum         quantum inicial (Round Robin)
     * @param cycleDurationMs duración del ciclo del reloj en milisegundos
     * @param disk            el disco, con su política y su cabezal
     * @param fileSystem      el sistema de archivos que vive en ese disco
     */
    public Cluster(int ramCapacity, SchedulingPolicy policy, int quantum, int cycleDurationMs,
            Disk disk, FileSystem fileSystem, EventLogger logger) {
        if (disk == null || fileSystem == null) {
            throw new IllegalArgumentException("El sistema necesita un disco y un sistema de archivos");
        }
        this.logger = (logger == null) ? EventLogger.SILENT : logger;
        this.computers = new Computer[]{new Computer(0, ramCapacity, policy, quantum, this.logger)};
        Kernel kernel = computers[0].getKernel();
        kernel.setWakeUpRouter(this::routeWakeUp);
        kernel.attachDisk(disk, fileSystem);
        disk.setCompletionListener(kernel);
        disk.setLogger(this.logger);      // las decisiones del disco salen en el log de eventos
        setNetworkLatency(0);
        this.disk = disk;
        this.fileSystem = fileSystem;
        this.clock = new GlobalClock(new ClockListener[]{computers[0], disk}, cycleDurationMs);
    }

    /** El disco, o null en el clúster del Proyecto 1. */
    public Disk getDisk() {
        return disk;
    }

    /** El sistema de archivos, o null en el clúster del Proyecto 1. */
    public FileSystem getFileSystem() {
        return fileSystem;
    }

    // ================================================================ operaciones de archivos (Proyecto 2)

    /**
     * La interfaz pide una operación (crear, leer, actualizar o eliminar) y aquí se crea el proceso
     * de E/S que la realiza. El proceso pasa por las colas del computador como cualquier otro.
     *
     * @throws IllegalArgumentException si no hay disco o los datos de la operación no son válidos
     */
    @Override
    public void requestFileOperation(FileOperation op) {
        if (disk == null || fileSystem == null) {
            throw new IllegalArgumentException("Este sistema no tiene disco");
        }
        if (op == null) {
            throw new IllegalArgumentException("Falta la operación");
        }
        String path = op.path();
        String shortName = path.substring(path.lastIndexOf('/') + 1);
        String name = op.operation() + "_" + (shortName.isEmpty() ? "raiz" : shortName);
        if (name.length() > 30) {
            name = name.substring(0, 30);
        }
        int memory = Math.min(16, computers[0].getKernel().getRam().getCapacity());
        ProcessControlBlock p = ProcessControlBlock.createFileIoProcess(
                name, 0, 1, memory, 500, 2, op.operation(), path);
        p.setOwner(op.owner());
        p.setRequesterMode(op.mode());
        if (op.operation() == IoOperation.UPDATE) {
            p.setNewName(op.newName());
        }
        if (op.operation() == IoOperation.CREATE) {
            p.setCreateSpec(op.createType(), op.createBlocks(), op.createPermission());
        }
        submit(p);
    }

    // ================================================================ red

    /** Entrega un proceso despertado al kernel de SU computador. */
    private void routeWakeUp(ProcessControlBlock process, int cycle) {
        getComputer(process.getAssignedComputerId()).getKernel().deliverWakeUp(process, cycle);
    }

    public final void setNetworkLatency(int cycles) {
        if (cycles < 0) {
            throw new IllegalArgumentException("La latencia de red no puede ser negativa");
        }
        this.networkLatency = cycles;
        for (Computer c : computers) {
            c.getKernel().setNetworkLatency(cycles);
        }
    }

    public int getNetworkLatency() {
        return networkLatency;
    }

    // ================================================================ buffers

    /**
     * Crea un buffer alojado en 'hostComputerId'. Ocupa RAM en ese computador.
     *
     * @throws IllegalArgumentException si el id se repite, el computador no
     *         existe o no hay RAM suficiente
     */
    public BoundedBuffer createBuffer(String id, int capacity, int hostComputerId) {
        Computer host = getComputer(hostComputerId);
        BoundedBuffer buffer = new BoundedBuffer(id, capacity, hostComputerId);   // valida id y capacidad
        buffersLock.acquireUninterruptibly();
        try {
            if (findBuffer(buffer.getId()) != null) {
                throw new IllegalArgumentException("Ya existe un buffer con id " + buffer.getId());
            }
            host.getKernel().hostBuffer(buffer);          // reserva la RAM (o lanza error)
            buffers.add(buffer);
            return buffer;
        } finally {
            buffersLock.release();
        }
    }

    /** @return el buffer con ese id, o null si no existe */
    public BoundedBuffer getBuffer(String id) {
        buffersLock.acquireUninterruptibly();
        try {
            return findBuffer(id);
        } finally {
            buffersLock.release();
        }
    }

    private BoundedBuffer findBuffer(String id) {
        for (int i = 0; i < buffers.getSize(); i++) {
            if (buffers.get(i).getId().equals(id)) {
                return buffers.get(i);
            }
        }
        return null;
    }

    public Object[] getBuffersSnapshot() {
        buffersLock.acquireUninterruptibly();
        try {
            return buffers.toArray();
        } finally {
            buffersLock.release();
        }
    }

    // ================================================================ procesos

    /**
     * Asignación automática (decisión de diseño, balanceo de carga): entre los
     * computadores cuya RAM total alcanza para el proceso, el que tenga menos
     * procesos vivos; en empate, el de menor id.
     *
     * @param memoryRequired memoria que pide el proceso
     * @throws IllegalArgumentException si ningún computador tiene RAM suficiente
     */
    public int pickComputerAutomatically(int memoryRequired) {
        int best = -1;
        int bestLoad = Integer.MAX_VALUE;
        for (int i = 0; i < computers.length; i++) {
            Kernel kernel = computers[i].getKernel();
            if (kernel.getRam().getCapacity() < memoryRequired) {
                continue;                       // nunca podría ser admitido ahí
            }
            int load = kernel.getActiveProcessCount();
            if (load < bestLoad) {
                best = i;
                bestLoad = load;
            }
        }
        if (best == -1) {
            throw new IllegalArgumentException("Ningún computador tiene RAM suficiente para "
                    + memoryRequired + " unidades de memoria");
        }
        return best;
    }

    /** Asignación automática sin considerar memoria (el computador con menos procesos vivos). */
    public int pickComputerAutomatically() {
        return pickComputerAutomatically(0);
    }

    /** Envía el proceso al computador indicado en su PCB. */
    public void submit(ProcessControlBlock process) {
        getComputer(process.getAssignedComputerId()).submit(process);
    }

    // ================================================================ reloj

    public void start()                      { clock.start(); }
    public void pause()                      { clock.pause(); }
    public void resume()                     { clock.resume(); }
    public void stop()                       { clock.stop(); }
    public void setCycleDurationMs(int ms)   { clock.setCycleDurationMs(ms); }
    public int getCurrentCycle()             { return clock.getCurrentCycle(); }

    /** Aviso al terminar cada ciclo completo (para la GUI y las métricas). */
    public void setAfterTickObserver(ClockListener observer) {
        clock.setAfterTickObserver(observer);
    }

    // ================================================================ consultas

    public Computer getComputer(int id) {
        if (id < 0 || id >= computers.length) {
            throw new IllegalArgumentException("No existe el computador " + id
                    + " (hay " + computers.length + ")");
        }
        return computers[id];
    }

    public int getComputerCount() {
        return computers.length;
    }

    public GlobalClock getClock() {
        return clock;
    }

    public EventLogger getLogger() {
        return logger;
    }
}
