/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package com.avilaos.kernel;

import com.avilaos.datastructures.CustomLinkedList;
import com.avilaos.disk.BlockMap;
import com.avilaos.disk.DiskCompletionListener;
import com.avilaos.disk.DiskPort;
import com.avilaos.disk.DiskRequest;
import com.avilaos.datastructures.CustomQueue;
import com.avilaos.enums.BlockReason;
import com.avilaos.enums.FileType;
import com.avilaos.enums.IoOperation;
import com.avilaos.enums.MicroInstruction;
import com.avilaos.enums.ProcessState;
import com.avilaos.enums.ProcessType;
import com.avilaos.enums.SchedulingPolicy;
import com.avilaos.enums.SemaphoreType;
import com.avilaos.enums.UserMode;
import com.avilaos.filesystem.FileSystem;
import com.avilaos.hardware.CPU;
import com.avilaos.hardware.IODevice;
import com.avilaos.hardware.RAM;
import com.avilaos.model.ProcessControlBlock;
import com.avilaos.scheduling.SchedulerFactory;
import com.avilaos.scheduling.SchedulingAlgorithm;
import com.avilaos.sync.BoundedBuffer;
import java.util.concurrent.Semaphore;

/**
 * Núcleo de UN computador.
 *
 * Responsabilidades:
 *  - Mantener las colas de estados: nuevos, listos, bloqueados y terminados.
 *  - Planificador de largo plazo: admitir de NEW a READY solo si hay RAM.
 *  - Planificador de corto plazo: elegir, despachar y expropiar según la política.
 *  - Ejecutar las llamadas al sistema de productores/consumidores
 *    (semWait, semSignal, añadir, extraer), una por ciclo.
 *  - Atender el fin de cada instrucción: terminar, pedir E/S, bloquearse, vencer deadline.
 *
 * Concurrencia (Semaphores de Java):
 *  - 'lock' protege las colas del kernel (hilo del computador + hilo de la GUI).
 *  - 'mailboxLock' protege el buzón de despertares, que pueden llenar otros
 *    computadores. Nunca se toma el lock de otro kernel: no hay interbloqueo.
 *  Orden de adquisición: lock del kernel -> lock del buffer -> mailboxLock.
 *
 * El orden de lo que ocurre en un ciclo está fijado en docs/ARQUITECTURA.md (sección 4).
 *
 * @author SebMarval
 * @author aleja
 */
public class Kernel implements DiskCompletionListener {

    private final int computerId;
    private final RAM ram;
    private final CPU cpu;
    private final IODevice dma = new IODevice("DMA", BlockReason.IO_WAIT);              // E/S
    private final IODevice network = new IODevice("Red", BlockReason.NETWORK_LATENCY); // latencia
    private final EventLogger logger;
    private final Semaphore lock = new Semaphore(1);

    private final CustomQueue<ProcessControlBlock> newQueue = new CustomQueue<>();
    private final CustomLinkedList<ProcessControlBlock> readyQueue = new CustomLinkedList<>();
    private final CustomLinkedList<ProcessControlBlock> blockedList = new CustomLinkedList<>();
    private final CustomLinkedList<ProcessControlBlock> terminatedList = new CustomLinkedList<>();
    private final CustomLinkedList<BoundedBuffer> hostedBuffers = new CustomLinkedList<>();

    private final CustomQueue<PendingWakeUp> wakeUpMailbox = new CustomQueue<>();
    private final Semaphore mailboxLock = new Semaphore(1);
    private WakeUpRouter wakeUpRouter;

    // Proyecto 2: disco y sistema de archivos (null en las pruebas del Proyecto 1)
    private DiskPort diskPort;
    private FileSystem fileSystem;
    private final CustomQueue<PendingDiskDone> diskMailbox = new CustomQueue<>();   // protegido por mailboxLock

    // Proyecto 2: suspensión (modelo de siete estados) y planificador a mediano plazo
    private BlockMap swapArea;                       // área de intercambio (el mapa de bloques del disco)
    private final CustomLinkedList<ProcessControlBlock> readySuspendedList = new CustomLinkedList<>();
    private final CustomLinkedList<ProcessControlBlock> blockedSuspendedList = new CustomLinkedList<>();
    private int memoryBeingFreed;                    // RAM de los que se están escribiendo al swap
    private ProcessControlBlock roomTarget;          // a quién se le está haciendo espacio (uno a la vez)
    private int suspensionCount;
    private int reactivationCount;

    private volatile int networkLatency;   // ciclos que bloquea cada acceso a un buffer remoto

    private SchedulingAlgorithm scheduler;
    private SchedulingPolicy policy;
    private int quantum;

    private int completedCount;
    private int deadlineMissedCount;
    private int currentCycle;

    public Kernel(int computerId, int ramCapacity, SchedulingPolicy initialPolicy,
            int initialQuantum, EventLogger logger) {
        this.computerId = computerId;
        this.ram = new RAM(ramCapacity);
        this.cpu = new CPU(computerId);
        this.logger = (logger == null) ? EventLogger.SILENT : logger;
        this.scheduler = SchedulerFactory.create(initialPolicy, initialQuantum);
        this.policy = initialPolicy;
        this.quantum = initialQuantum;
        this.wakeUpRouter = this::deliverWakeUp;   // por defecto: solo procesos propios
    }

    // ================================================================ creación

    /**
     * Recibe un proceso nuevo (desde la GUI o la carga inicial) y lo deja en la
     * cola de nuevos. No reserva RAM: eso ocurre en la admisión.
     *
     * @throws IllegalArgumentException si el proceso es de otro computador,
     *         pide más memoria que la RAM total o es productor/consumidor sin buffer
     */
    public void submit(ProcessControlBlock process) {
        if (process.getAssignedComputerId() != computerId) {
            throw new IllegalArgumentException("P" + process.getId()
                    + " pertenece al computador " + process.getAssignedComputerId());
        }
        if (process.getMemoryRequired() > ram.getCapacity()) {
            throw new IllegalArgumentException("P" + process.getId() + " pide "
                    + process.getMemoryRequired() + " de memoria y la RAM total es " + ram.getCapacity());
        }
        if (process.isSyncProcess() && process.getBuffer() == null) {
            throw new IllegalArgumentException("P" + process.getId() + " es " + process.getType()
                    + " y no tiene buffer asignado");
        }
        lock.acquireUninterruptibly();
        try {
            process.markArrival(currentCycle);
            newQueue.enqueue(process);
            logger.log(currentCycle, computerId, "P" + process.getId() + " (" + process.getName() + ") creado -> NEW");
        } finally {
            lock.release();
        }
    }

    /**
     * Aloja un buffer en este computador: reserva su memoria en la RAM.
     *
     * @throws IllegalArgumentException si el buffer es de otro computador o no hay RAM
     */
    public void hostBuffer(BoundedBuffer buffer) {
        if (buffer.getHostComputerId() != computerId) {
            throw new IllegalArgumentException("El buffer " + buffer.getId()
                    + " pertenece al computador " + buffer.getHostComputerId());
        }
        lock.acquireUninterruptibly();
        try {
            if (!ram.allocate(buffer.getMemoryRequired())) {
                throw new IllegalArgumentException("RAM insuficiente para el buffer " + buffer.getId()
                        + " (pide " + buffer.getMemoryRequired() + ", libre " + ram.getFree() + ")");
            }
            hostedBuffers.add(buffer);
            logger.log(currentCycle, computerId, "Buffer " + buffer.getId() + " creado (capacidad "
                    + buffer.getCapacity() + ", " + ram.getUsed() + "/" + ram.getCapacity() + " RAM)");
        } finally {
            lock.release();
        }
    }

    // ================================================================ ciclo

    /** Ejecuta UN ciclo completo del computador, en el orden fijado por el contrato. */
    public void runCycle(int cycle) {
        lock.acquireUninterruptibly();
        try {
            currentCycle = cycle;
            processWakeUps();           // 0. despertares por semSignal (del ciclo anterior)
            processDiskCompletions();   // 0b. solicitudes que el disco terminó en el ciclo anterior
            tickDevices();              // 1. DMA (E/S) y red (latencia)
            admitNewProcesses();        // 2. largo plazo
            scheduleIfNeeded();         // 3. corto plazo
            executeInstruction();       // 4. una instrucción (usuario o SO)
            handleAfterExecution();     // 5. termina / pide E/S / se bloquea
            updateDeadlines();          // 6. deadlines en todos los estados
            accountCycle();             // 7. métricas del ciclo
        } finally {
            lock.release();
        }
    }

    // 0 -----------------------------------------------------------------
    /**
     * Mueve a READY los procesos despertados por un semSignal. Si el proceso ya
     * no está bloqueado (p. ej. venció su deadline), el aviso se descarta.
     */
    private void processWakeUps() {
        mailboxLock.acquireUninterruptibly();
        try {
            int pending = wakeUpMailbox.size();
            for (int k = 0; k < pending; k++) {
                PendingWakeUp w = wakeUpMailbox.dequeue();
                if (w.cycle() >= currentCycle) {
                    wakeUpMailbox.enqueue(w);       // se envió en este mismo ciclo: espera al siguiente
                    continue;
                }
                ProcessControlBlock p = w.process();
                if (blockedList.remove(p)) {
                    BlockReason reason = p.getBlockReason();
                    p.setState(ProcessState.READY);
                    readyQueue.add(p);
                    logger.log(currentCycle, computerId, "P" + p.getId() + " despertado (" + reason + ") -> READY");
                } else if (blockedSuspendedList.remove(p)) {
                    toReadySuspended(p, "despertado (" + p.getBlockReason() + ")");
                }
            }
        } finally {
            mailboxLock.release();
        }
    }

    /** Buzón de despertares: lo llaman otros computadores (o este mismo). */
    public void deliverWakeUp(ProcessControlBlock process, int cycle) {
        if (process.getAssignedComputerId() != computerId) {
            throw new IllegalArgumentException("P" + process.getId() + " no pertenece al computador " + computerId);
        }
        mailboxLock.acquireUninterruptibly();
        try {
            wakeUpMailbox.enqueue(new PendingWakeUp(process, cycle));
        } finally {
            mailboxLock.release();
        }
    }

    /** Un despertar pendiente y el ciclo en que se produjo el semSignal. */
    private record PendingWakeUp(ProcessControlBlock process, int cycle) { }

    // ================================================================ disco (Proyecto 2)

    /** Conecta el disco y el sistema de archivos. El kernel no conoce la clase Disk: solo su puerto. */
    public void attachDisk(DiskPort port, FileSystem fs) {
        if (port == null || fs == null) {
            throw new IllegalArgumentException("El disco y el sistema de archivos son obligatorios");
        }
        this.diskPort = port;
        this.fileSystem = fs;
        this.swapArea = fs.getBlockMap();
    }

    /**
     * El disco terminó una solicitud. Lo llama el HILO DEL DISCO: aquí no se toca
     * ninguna cola del kernel, solo se deja el aviso en un buzón. El kernel lo
     * procesa en el paso 0 del ciclo SIGUIENTE (mismo criterio determinista que
     * los despertares del Proyecto 1).
     */
    @Override
    public void onRequestCompleted(DiskRequest request, int cycle) {
        mailboxLock.acquireUninterruptibly();
        try {
            diskMailbox.enqueue(new PendingDiskDone(request.getProcessId(), request.getOperation(), cycle));
        } finally {
            mailboxLock.release();
        }
    }

    /** Una solicitud que el disco terminó y el ciclo en que terminó. */
    private record PendingDiskDone(int processId, IoOperation operation, int cycle) { }

    // 0b ----------------------------------------------------------------
    /**
     * Cada solicitud terminada: se aplica el cambio en el sistema de archivos y
     * el proceso vuelve a READY (o termina, si ya no le quedan instrucciones).
     * Si el cambio ya no se puede aplicar (p. ej. el archivo se borró mientras
     * esperaba), el proceso termina y el motivo queda en el log. Si el proceso
     * ya no está bloqueado (venció su deadline), el aviso se descarta.
     */
    private void processDiskCompletions() {
        mailboxLock.acquireUninterruptibly();
        try {
            int pending = diskMailbox.size();
            for (int k = 0; k < pending; k++) {
                PendingDiskDone done = diskMailbox.dequeue();
                if (done.cycle() >= currentCycle) {
                    diskMailbox.enqueue(done);      // terminó en este mismo ciclo: espera al siguiente
                    continue;
                }
                if (done.operation() == IoOperation.SUSPEND) {
                    finishSwapOut(done.processId());
                    continue;
                }
                if (done.operation() == IoOperation.REACTIVATE) {
                    finishSwapIn(done.processId());
                    continue;
                }
                ProcessControlBlock p = removeBlockedById(done.processId(), BlockReason.DISK_IO);
                boolean suspended = false;
                if (p == null) {
                    p = removeById(blockedSuspendedList, done.processId(), BlockReason.DISK_IO);
                    suspended = (p != null);
                }
                if (p == null) {
                    continue;                       // ya no espera (venció su deadline): se descarta
                }
                String error = applyFileOperation(p);
                if (error != null) {
                    terminateAnywhere(p, p.getIoOperation() + " " + p.getTargetPath() + " rechazada: " + error);
                } else if (p.isFinished()) {
                    terminateAnywhere(p, "completó " + p.getIoOperation() + " " + p.getTargetPath());
                } else if (suspended) {
                    toReadySuspended(p, "completó " + p.getIoOperation() + " " + p.getTargetPath() + " en el disco");
                } else {
                    p.setState(ProcessState.READY);
                    readyQueue.add(p);
                    logger.log(currentCycle, computerId, "P" + p.getId() + " completó " + p.getIoOperation()
                            + " " + p.getTargetPath() + " en el disco -> READY");
                }
            }
        } finally {
            mailboxLock.release();
        }
    }

    private ProcessControlBlock removeBlockedById(int processId, BlockReason reason) {
        return removeById(blockedList, processId, reason);
    }

    /** Saca de 'list' al proceso con ese id (y ese motivo de bloqueo, si no es null). */
    private static ProcessControlBlock removeById(CustomLinkedList<ProcessControlBlock> list, int processId,
            BlockReason reason) {
        for (int i = 0; i < list.getSize(); i++) {
            ProcessControlBlock p = list.get(i);
            if (p.getId() == processId && (reason == null || p.getBlockReason() == reason)) {
                return list.removeAt(i);
            }
        }
        return null;
    }

    private static ProcessControlBlock findById(CustomLinkedList<ProcessControlBlock> list, int processId) {
        for (int i = 0; i < list.getSize(); i++) {
            if (list.get(i).getId() == processId) {
                return list.get(i);
            }
        }
        return null;
    }

    // 1 -----------------------------------------------------------------
    /**
     * Los dispositivos (componentes de hardware que reaccionan al reloj) avanzan
     * un ciclo; el kernel pasa a READY a los procesos cuyo servicio terminó.
     */
    private void tickDevices() {
        wakeFromDevice(dma);
        wakeFromDevice(network);
    }

    private void wakeFromDevice(IODevice device) {
        device.onTick(currentCycle);
        ProcessControlBlock[] done = device.drainCompleted();
        for (ProcessControlBlock p : done) {
            if (blockedList.remove(p)) {
                p.setState(ProcessState.READY);
                readyQueue.add(p);
                logger.log(currentCycle, computerId, "P" + p.getId() + " termina " + device.getReason()
                        + " (" + device.getName() + ") -> READY");
            } else if (blockedSuspendedList.remove(p)) {
                toReadySuspended(p, "termina " + device.getReason() + " (" + device.getName() + ")");
            }
        }
    }

    // 2 -----------------------------------------------------------------
    /**
     * Admisión FIFO estricta: si el primero de la cola no cabe, nadie lo
     * adelanta (evita que un proceso grande sufra inanición).
     */
    private void admitNewProcesses() {
        while (true) {
            ProcessControlBlock target = nextInLine();
            if (target == null) {
                return;
            }
            boolean reactivation = target.getState() == ProcessState.READY_SUSPENDED;
            if (ram.allocate(target.getMemoryRequired())) {
                if (target == roomTarget) {
                    roomTarget = null;
                }
                if (reactivation) {
                    startSwapIn(target);
                } else {
                    newQueue.remove(target);
                    target.setState(ProcessState.READY);
                    readyQueue.add(target);
                    logger.log(currentCycle, computerId, "P" + target.getId() + " admitido -> READY ("
                            + ram.getUsed() + "/" + ram.getCapacity() + " RAM)");
                }
                continue;
            }
            makeRoomFor(target);     // no cabe: el planificador a mediano plazo intenta hacerle espacio
            return;                  // uno a la vez: nadie adelanta al primero de la fila
        }
    }

    // ================================================================ planificador a mediano plazo

    /**
     * El primero de la fila para entrar a la RAM: primero los Listo/Suspendido
     * (FIFO; ya estaban en el sistema y así no sufren inanición), después el
     * primero de NUEVOS. Un Listo/Suspendido que todavía se está escribiendo al
     * swap no puede volver hasta que termine esa escritura.
     */
    private ProcessControlBlock nextInLine() {
        // Uno a la vez: si ya se suspendió a alguien para hacerle espacio a un proceso,
        // la RAM que se libere es para ÉL, aunque otro se ponga antes en la fila.
        if (roomTarget != null) {
            boolean stillWaiting = roomTarget.getState() == ProcessState.NEW
                    || (roomTarget.getState() == ProcessState.READY_SUSPENDED && roomTarget.getSwapInProgress() == null);
            if (stillWaiting) {
                return roomTarget;
            }
            roomTarget = null;           // ya entró, o terminó (por ejemplo, venció su deadline)
        }
        for (int i = 0; i < readySuspendedList.getSize(); i++) {
            ProcessControlBlock p = readySuspendedList.get(i);
            if (p.getSwapInProgress() == null) {
                return p;
            }
        }
        return newQueue.peek();
    }

    /**
     * El primero de la fila no cabe en la RAM. Si suspendiendo procesos
     * bloqueados se puede liberar lo que le falta, se suspenden (criterio de
     * selección en chooseVictim) hasta cubrirlo. Si ni suspendiendo a todos
     * los bloqueados alcanza, no se suspende a nadie: sería costo de disco sin
     * beneficio. Lo que ya se está liberando (escrituras al swap en curso)
     * cuenta como liberado, para no suspender de más.
     */
    private void makeRoomFor(ProcessControlBlock target) {
        if (diskPort == null || swapArea == null) {
            return;                              // sin disco (Proyecto 1): se espera como siempre
        }
        int missing = target.getMemoryRequired() - ram.getFree() - memoryBeingFreed;
        if (missing <= 0) {
            return;                              // ya se le está haciendo espacio
        }
        int suspendable = 0;
        for (int i = 0; i < blockedList.getSize(); i++) {
            suspendable += blockedList.get(i).getMemoryRequired();
        }
        if (suspendable < missing) {
            return;
        }
        while (missing > 0) {
            ProcessControlBlock victim = chooseVictim();
            if (victim == null || !startSwapOut(victim, target)) {
                return;                          // swap lleno: se espera a que se libere memoria
            }
            roomTarget = target;                 // la RAM que se libere es para él
            missing -= victim.getMemoryRequired();
        }
    }

    /**
     * Criterio de selección: el bloqueado de MENOR prioridad (número mayor).
     * Empate: el que se bloqueó primero (el más antiguo de la lista). Es el que
     * menos falta hace en RAM: no puede ejecutar y es el menos importante.
     */
    private ProcessControlBlock chooseVictim() {
        ProcessControlBlock victim = null;
        for (int i = 0; i < blockedList.getSize(); i++) {
            ProcessControlBlock p = blockedList.get(i);
            if (victim == null || p.getPriority() > victim.getPriority()) {
                victim = p;
            }
        }
        return victim;
    }

    /** Posición del cabezal para el swap: el último bloque de archivos, la entrada al área de intercambio. */
    private int swapEntryBlock() {
        return swapArea.getFileAreaBlocks() - 1;
    }

    /**
     * Suspender: el proceso pasa a Bloqueado/Suspendido, se le reservan sus
     * bloques en el swap y se pide al disco la escritura. Su RAM se libera
     * cuando el disco termine (finishSwapOut). Devuelve false si el swap está lleno.
     */
    private boolean startSwapOut(ProcessControlBlock victim, ProcessControlBlock target) {
        int memory = victim.getMemoryRequired();
        int start = swapArea.allocateSwap("P" + victim.getId(), memory);
        if (start == -1) {
            logger.log(currentCycle, computerId, "Swap lleno: no se puede suspender a P" + victim.getId()
                    + " (" + swapArea.freeSwapBlocks() + " bloques libres, necesita " + memory + ")");
            return false;
        }
        blockedList.remove(victim);
        victim.setState(ProcessState.BLOCKED_SUSPENDED);     // conserva su motivo de bloqueo
        victim.setSwapStartBlock(start);
        victim.setSwapInProgress(IoOperation.SUSPEND);
        blockedSuspendedList.add(victim);
        memoryBeingFreed += memory;
        suspensionCount++;
        diskPort.submitRequest(new DiskRequest(swapEntryBlock(), IoOperation.SUSPEND, victim.getId(), currentCycle, memory));
        logger.log(currentCycle, computerId, "Se suspende al proceso P" + victim.getId() + " para "
                + (target.getState() == ProcessState.NEW ? "admitir" : "reactivar") + " a P" + target.getId()
                + " (" + memory + " bloques al swap desde el " + start + ") -> BLOCKED_SUSPENDED");
        return true;
    }

    /** El disco terminó de escribir al swap: recién ahora se libera su RAM. */
    private void finishSwapOut(int processId) {
        ProcessControlBlock p = findById(blockedSuspendedList, processId);
        if (p == null) {
            p = findById(readySuspendedList, processId);    // su evento ocurrió mientras se escribía
        }
        if (p == null || p.getSwapInProgress() != IoOperation.SUSPEND) {
            return;
        }
        p.setSwapInProgress(null);
        ram.free(p.getMemoryRequired());
        memoryBeingFreed -= p.getMemoryRequired();
        logger.log(currentCycle, computerId, "P" + p.getId() + " quedó en el swap: libera " + p.getMemoryRequired()
                + " de RAM (" + ram.getUsed() + "/" + ram.getCapacity() + ")");
    }

    /** Reactivar: la RAM ya quedó reservada; se pide al disco que lea su imagen del swap. */
    private void startSwapIn(ProcessControlBlock p) {
        p.setSwapInProgress(IoOperation.REACTIVATE);
        reactivationCount++;
        diskPort.submitRequest(new DiskRequest(swapEntryBlock(), IoOperation.REACTIVATE, p.getId(), currentCycle,
                p.getMemoryRequired()));
        logger.log(currentCycle, computerId, "Se reactiva al proceso P" + p.getId() + " (" + p.getMemoryRequired()
                + " bloques desde el swap, RAM reservada " + ram.getUsed() + "/" + ram.getCapacity() + ")");
    }

    /** El disco terminó de leer del swap: se liberan sus bloques y vuelve a READY. */
    private void finishSwapIn(int processId) {
        ProcessControlBlock p = findById(readySuspendedList, processId);
        if (p == null || p.getSwapInProgress() != IoOperation.REACTIVATE) {
            return;
        }
        readySuspendedList.remove(p);
        swapArea.free(p.getSwapStartBlock());
        p.setSwapStartBlock(-1);
        p.setSwapInProgress(null);
        p.setState(ProcessState.READY);
        readyQueue.add(p);
        logger.log(currentCycle, computerId, "P" + p.getId() + " volvió a la RAM -> READY");
    }

    /** Un Bloqueado/Suspendido cuyo evento ocurre pasa a Listo/Suspendido (sigue en el swap). */
    private void toReadySuspended(ProcessControlBlock p, String event) {
        p.setState(ProcessState.READY_SUSPENDED);
        readySuspendedList.add(p);
        logger.log(currentCycle, computerId, "P" + p.getId() + " " + event + " -> READY_SUSPENDED");
    }

    /** ¿Ocupa RAM? En RAM, o suspendiéndose (aún no se libera), o reactivándose (ya reservada). */
    private static boolean holdsRam(ProcessControlBlock p) {
        if (p.getState() == ProcessState.NEW) {
            return false;
        }
        return !p.isSuspended() || p.getSwapInProgress() != null;
    }

    /**
     * Termina un proceso esté donde esté (dentro o fuera de la RAM): devuelve
     * su RAM si la tenía, sus bloques de swap y cualquier solicitud pendiente.
     * El proceso ya debe estar fuera de su lista.
     */
    private void terminateAnywhere(ProcessControlBlock p, String reason) {
        boolean holds = holdsRam(p);
        if (p.getSwapInProgress() == IoOperation.SUSPEND) {
            memoryBeingFreed -= p.getMemoryRequired();
        }
        if (p.getSwapInProgress() != null && diskPort != null) {
            diskPort.cancelRequest(p.getId());
        }
        if (p.getSwapStartBlock() >= 0) {
            swapArea.free(p.getSwapStartBlock());
            p.setSwapStartBlock(-1);
        }
        p.setSwapInProgress(null);
        terminate(p, holds, reason);
    }

    // 3 -----------------------------------------------------------------
    private void scheduleIfNeeded() {
        ProcessControlBlock running = cpu.getRunning();

        // Expropiación: la política activa decide si el proceso en CPU debe salir.
        if (running != null && scheduler.shouldPreempt(running, readyQueue, cpu.getQuantumUsed())) {
            cpu.release();
            running.setState(ProcessState.READY);
            readyQueue.add(running);
            logger.log(currentCycle, computerId, "P" + running.getId() + " expropiado (" + policy + ") -> READY");
        }

        // Despacho: si la CPU está libre, la política elige al siguiente.
        if (cpu.isIdle() && !readyQueue.isEmpty()) {
            int index = scheduler.selectNextIndex(readyQueue);
            if (index != -1) {
                ProcessControlBlock next = readyQueue.removeAt(index);
                cpu.dispatch(next, currentCycle);
                logger.log(currentCycle, computerId, "P" + next.getId() + " despachado -> RUNNING");
            }
        }
    }

    // 4 -----------------------------------------------------------------
    private void executeInstruction() {
        ProcessControlBlock running = cpu.getRunning();
        if (running == null) {
            cpu.onTick(currentCycle);           // ciclo ocioso
            return;
        }
        if (running.needsDiskRequest()) {
            cpu.executeKernelInstruction();     // la solicitud al disco es 1 instrucción en modo SO
            requestDisk(running);
            return;
        }
        MicroInstruction instruction = running.currentInstruction();
        if (!instruction.isSystemCall()) {
            cpu.onTick(currentCycle);           // instrucción de usuario
            return;
        }
        if (needsNetworkLatency(running)) {
            requestRemoteAccess(running);       // el acceso remoto espera la latencia primero
            return;
        }
        cpu.executeKernelInstruction();         // llamada al sistema: modo SO
        running.setRemoteAccessReady(false);    // la próxima operación remota vuelve a pagar latencia
        executeSystemCall(running, instruction);
    }

    /** D6: cada operación sobre un buffer de OTRO computador paga la latencia de red. */
    private boolean needsNetworkLatency(ProcessControlBlock p) {
        return networkLatency > 0
                && p.getBuffer().isRemoteFor(computerId)
                && !p.isRemoteAccessReady();
    }

    /**
     * El kernel envía la solicitud por la red (1 ciclo de CPU en modo SO, sin
     * avanzar el PC) y el proceso queda BLOCKED por NETWORK_LATENCY. La
     * operación se ejecuta cuando vuelve a la CPU, 'networkLatency' ciclos después.
     */
    private void requestRemoteAccess(ProcessControlBlock p) {
        cpu.executeKernelOverhead();
        network.startService(p, networkLatency);
        p.setRemoteAccessReady(true);
        logger.log(currentCycle, computerId, "P" + p.getId() + " accede al buffer remoto "
                + p.getBuffer().getId() + "@PC" + p.getBuffer().getHostComputerId()
                + " (" + p.currentInstruction() + ") -> BLOCKED NETWORK_LATENCY " + networkLatency + " ciclos");
    }

    /** Semántica de una llamada al sistema sobre el buffer del proceso. */
    private void executeSystemCall(ProcessControlBlock p, MicroInstruction instruction) {
        BoundedBuffer buffer = p.getBuffer();
        String who = "P" + p.getId();
        if (instruction.isWait()) {
            SemaphoreType sem = instruction.getSemaphore();
            if (buffer.semWait(sem, p)) {
                p.block(sem.getBlockReason());  // sale de la CPU en el paso 5
                logger.log(currentCycle, computerId, who + " semWait(" + buffer.getId() + "." + sem.getSymbol()
                        + ") -> BLOCKED " + sem.getBlockReason());
            }
        } else if (instruction.isSignal()) {
            signalAndRoute(buffer, instruction.getSemaphore());
        } else if (instruction == MicroInstruction.ADD_ITEM) {
            int item = buffer.add();
            p.countItemProcessed();
            logger.log(currentCycle, computerId, who + " produce elemento #" + item + " en " + buffer.getId());
        } else if (instruction == MicroInstruction.REMOVE_ITEM) {
            int item = buffer.remove();
            p.countItemProcessed();
            logger.log(currentCycle, computerId, who + " consume elemento #" + item + " de " + buffer.getId());
        }
    }

    /** semSignal + entrega del proceso despertado al kernel de su computador. */
    private void signalAndRoute(BoundedBuffer buffer, SemaphoreType sem) {
        ProcessControlBlock woken = buffer.semSignal(sem);
        if (woken != null) {
            wakeUpRouter.deliverWakeUp(woken, currentCycle);
        }
    }

    // ---------------------------------------------------------------- E/S de archivos

    /**
     * El proceso de E/S genera su solicitud: primero se comprueban permisos y
     * estado (si falla, el proceso termina sin llegar al disco); si todo está
     * bien, la solicitud entra a la cola del disco y el proceso queda BLOCKED
     * por DISK_IO hasta que el disco la atienda.
     */
    private void requestDisk(ProcessControlBlock p) {
        String error = (diskPort == null) ? "no hay disco conectado" : checkFileOperation(p);
        if (error != null) {
            cpu.release();
            terminate(p, true, p.getIoOperation() + " " + p.getTargetPath() + " rechazada: " + error);
            return;
        }
        int block = requestBlock(p);
        int count = requestBlockCount(p);
        diskPort.submitRequest(new DiskRequest(block, p.getIoOperation(), p.getId(), currentCycle, count));
        p.block(BlockReason.DISK_IO);           // sale de la CPU en el paso 5
        logger.log(currentCycle, computerId, "P" + p.getId() + " solicita " + p.getIoOperation() + " "
                + p.getTargetPath() + " al disco (bloque " + block + ", " + count + " bloque(s)) -> BLOCKED DISK_IO");
    }

    /** Los procesos de admin tienen todos los permisos; los demás, los de modo usuario. */
    private static UserMode modeOf(ProcessControlBlock p) {
        return "admin".equals(p.getOwner()) ? UserMode.ADMINISTRATOR : UserMode.USER;
    }

    private String checkFileOperation(ProcessControlBlock p) {
        UserMode mode = modeOf(p);
        String path = p.getTargetPath();
        return switch (p.getIoOperation()) {
            case CREATE -> p.getCreateType() == null ? "falta indicar qué se crea"
                    : fileSystem.checkCreate(path, p.getCreateType(), p.getCreateBlocks(), mode, p.getOwner());
            case READ -> fileSystem.checkRead(path, mode, p.getOwner());
            case UPDATE -> fileSystem.checkUpdate(path, p.getNewName(), mode, p.getOwner());
            case DELETE -> fileSystem.checkDelete(path, mode, p.getOwner());
            default -> "operación no válida para un proceso de archivos";
        };
    }

    /**
     * Posición del cabezal que necesita la solicitud. Un archivo existente: su
     * primer bloque. CREATE de archivo: el primer bloque libre, donde empezará.
     * Los directorios no tienen bloques: sus metadatos se toman en el bloque 0.
     */
    private int requestBlock(ProcessControlBlock p) {
        if (p.getIoOperation() == IoOperation.CREATE) {
            return p.getCreateType() == FileType.FILE ? Math.max(0, fileSystem.firstFreeBlock()) : 0;
        }
        FileSystem.EntryInfo info = fileSystem.info(p.getTargetPath());
        return (info == null || info.startBlock() < 0) ? 0 : info.startBlock();
    }

    /** READ transfiere todo el archivo; CREATE escribe sus bloques; UPDATE y DELETE solo metadatos (1). */
    private int requestBlockCount(ProcessControlBlock p) {
        if (p.getIoOperation() == IoOperation.CREATE) {
            return Math.max(1, p.getCreateBlocks());
        }
        if (p.getIoOperation() == IoOperation.READ) {
            FileSystem.EntryInfo info = fileSystem.info(p.getTargetPath());
            return (info == null) ? 1 : Math.max(1, info.blocks());
        }
        return 1;
    }

    /** Aplica el cambio cuando el disco terminó. Devuelve null o el motivo por el que ya no se pudo. */
    private String applyFileOperation(ProcessControlBlock p) {
        String path = p.getTargetPath();
        String creator = "P" + p.getId();
        try {
            switch (p.getIoOperation()) {
                case CREATE -> {
                    if (p.getCreateType() == FileType.FILE) {
                        fileSystem.createFile(path, p.getCreateBlocks(), p.getOwner(), p.getCreatePermission(), creator);
                    } else {
                        fileSystem.createDirectory(path, p.getOwner(), p.getCreatePermission(), creator);
                    }
                }
                case READ -> {
                    if (!fileSystem.exists(path)) {
                        return "'" + path + "' ya no existe";
                    }
                }
                case UPDATE -> fileSystem.rename(path, p.getNewName());
                case DELETE -> fileSystem.delete(path);
                default -> {
                    return "operación no válida para un proceso de archivos";
                }
            }
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    // 5 -----------------------------------------------------------------
    private void handleAfterExecution() {
        ProcessControlBlock running = cpu.getRunning();
        if (running == null) {
            return;
        }
        if (running.getState() == ProcessState.BLOCKED) {
            cpu.release();                       // se bloqueó en un semáforo, espera la red o el disco
            blockedList.add(running);            // (primero: un proceso de E/S de 1 instrucción espera al disco)
        } else if (running.isFinished()) {
            cpu.release();
            terminate(running, true, "terminó sus instrucciones");
        } else if (running.needsIO()) {
            cpu.release();
            dma.startService(running, running.getIoDurationCycles());
            blockedList.add(running);
            logger.log(currentCycle, computerId, "P" + running.getId() + " solicita E/S por "
                    + running.getIoDurationCycles() + " ciclos -> BLOCKED");
        }
    }

    // 6 -----------------------------------------------------------------
    private void updateDeadlines() {
        ProcessControlBlock running = cpu.getRunning();
        if (running != null && running.tickDeadline()) {
            cpu.release();
            expire(running, true);
        }
        expireFromList(readyQueue, true);
        expireFromList(blockedList, true);
        expireSuspended(readySuspendedList);
        expireSuspended(blockedSuspendedList);
        expireFromNewQueue();
    }

    /** El deadline sigue corriendo en el swap (D7). */
    private void expireSuspended(CustomLinkedList<ProcessControlBlock> list) {
        int i = 0;
        while (i < list.getSize()) {
            ProcessControlBlock p = list.get(i);
            if (p.tickDeadline()) {
                list.removeAt(i);
                deadlineMissedCount++;
                dma.cancel(p);
                network.cancel(p);
                while (diskPort != null && diskPort.cancelRequest(p.getId())) {
                    // retira todas sus solicitudes (archivo y swap)
                }
                releaseSyncResources(p);
                terminateAnywhere(p, "deadline vencido");
            } else {
                i++;
            }
        }
    }

    private void expireFromList(CustomLinkedList<ProcessControlBlock> list, boolean holdsMemory) {
        int i = 0;
        while (i < list.getSize()) {
            ProcessControlBlock p = list.get(i);
            if (p.tickDeadline()) {
                list.removeAt(i);
                expire(p, holdsMemory);
            } else {
                i++;
            }
        }
    }

    /** Los procesos en NEW también envejecen, pero no tienen RAM que liberar. */
    private void expireFromNewQueue() {
        int pending = newQueue.size();
        for (int k = 0; k < pending; k++) {
            ProcessControlBlock p = newQueue.dequeue();
            if (p.tickDeadline()) {
                expire(p, false);
            } else {
                newQueue.enqueue(p);   // rota la cola conservando el orden FIFO
            }
        }
    }

    private void expire(ProcessControlBlock p, boolean holdsMemory) {
        deadlineMissedCount++;
        dma.cancel(p);                 // si moría esperando un dispositivo, se retira de él
        network.cancel(p);
        if (p.getBlockReason() == BlockReason.DISK_IO && diskPort != null) {
            diskPort.cancelRequest(p.getId());   // su solicitud sale de la cola del disco
        }
        releaseSyncResources(p);
        terminate(p, holdsMemory, "deadline vencido");
    }

    /**
     * Un productor/consumidor que muere a mitad de su micro-programa podría
     * dejar el buffer trabado (por ejemplo, con el mutex s tomado). Aquí se
     * devuelve todo lo que tenía, según la posición en la que murió.
     *
     *  - Si murió esperando en un semáforo, se cancela esa espera.
     *  - Si tenía el mutex s, se libera.
     *  - Productor: si reservó espacio (e) y no añadió, lo devuelve;
     *    si añadió y no avisó (n), avisa.
     *  - Consumidor: si tomó un elemento (n) y no extrajo, lo devuelve;
     *    si extrajo y no avisó (e), avisa.
     */
    private void releaseSyncResources(ProcessControlBlock p) {
        if (!p.isSyncProcess() || p.getState() == ProcessState.NEW) {
            return;
        }
        BoundedBuffer buffer = p.getBuffer();
        int pos = p.positionInItem(p.getProgramCounter());   // próxima instrucción
        if (buffer.cancelWait(p)) {
            pos--;                                            // ese semWait queda deshecho
        }
        int base = (p.getType() == ProcessType.PRODUCER) ? p.getWorkCycles() : 0;
        int step = pos - base;   // instrucciones de SO ya completadas en este elemento (0..5)

        boolean holdsMutex = (step == 2 || step == 3);
        if (holdsMutex) {
            signalAndRoute(buffer, SemaphoreType.MUTEX);
        }
        if (p.getType() == ProcessType.PRODUCER) {
            if (step == 1 || step == 2) {
                signalAndRoute(buffer, SemaphoreType.EMPTY);   // devuelve el espacio reservado
            } else if (step == 3 || step == 4) {
                signalAndRoute(buffer, SemaphoreType.FULL);    // el elemento ya está: avisa
            }
        } else {
            if (step == 1 || step == 2) {
                signalAndRoute(buffer, SemaphoreType.FULL);    // devuelve el elemento reservado
            } else if (step == 3 || step == 4) {
                signalAndRoute(buffer, SemaphoreType.EMPTY);   // ya extrajo: avisa espacio libre
            }
        }
    }

    // 7 -----------------------------------------------------------------
    private void accountCycle() {
        for (int i = 0; i < readyQueue.getSize(); i++) {
            readyQueue.get(i).accountCycle();
        }
        for (int i = 0; i < blockedList.getSize(); i++) {
            blockedList.get(i).accountCycle();
        }
    }

    // ================================================================ terminación

    private void terminate(ProcessControlBlock p, boolean holdsMemory, String reason) {
        p.setState(ProcessState.TERMINATED);
        p.markFinish(currentCycle);
        if (holdsMemory) {
            ram.free(p.getMemoryRequired());
        }
        if (!p.isDeadlineMissed()) {
            completedCount++;
        }
        terminatedList.add(p);
        logger.log(currentCycle, computerId, "P" + p.getId() + " " + reason + " -> TERMINATED");
    }

    // ================================================================ política

    /** Cambia la política de planificación en plena ejecución (independiente por computador). */
    public void setSchedulingPolicy(SchedulingPolicy newPolicy, int newQuantum) {
        SchedulingAlgorithm created = SchedulerFactory.create(newPolicy, newQuantum); // valida antes de tomar el lock
        lock.acquireUninterruptibly();
        try {
            this.scheduler = created;
            this.policy = newPolicy;
            this.quantum = newQuantum;
            logger.log(currentCycle, computerId, "Política cambiada a " + newPolicy
                    + (newPolicy == SchedulingPolicy.ROUND_ROBIN ? " (quantum " + newQuantum + ")" : ""));
        } finally {
            lock.release();
        }
    }

    /** Latencia de red en ciclos (0 = sin latencia). */
    public void setNetworkLatency(int cycles) {
        if (cycles < 0) {
            throw new IllegalArgumentException("La latencia de red no puede ser negativa");
        }
        this.networkLatency = cycles;
    }

    public int getNetworkLatency() {
        return networkLatency;
    }

    /** Procesos vivos en el computador (nuevos + listos + bloqueados + en CPU). */
    public int getActiveProcessCount() {
        lock.acquireUninterruptibly();
        try {
            return newQueue.size() + readyQueue.getSize() + blockedList.getSize()
                    + readySuspendedList.getSize() + blockedSuspendedList.getSize()
                    + (cpu.isIdle() ? 0 : 1);
        } finally {
            lock.release();
        }
    }

    /** Lo usa el Cluster para que los semSignal remotos lleguen al kernel correcto. */
    public void setWakeUpRouter(WakeUpRouter router) {
        if (router == null) {
            throw new IllegalArgumentException("El router no puede ser null");
        }
        this.wakeUpRouter = router;
    }

    // ================================================================ consultas (copias para la GUI)

    public Object[] getNewSnapshot()        { return snapshot(newQueue); }
    public Object[] getReadySnapshot()      { return snapshot(readyQueue); }
    public Object[] getBlockedSnapshot()    { return snapshot(blockedList); }
    public Object[] getReadySuspendedSnapshot()   { return snapshot(readySuspendedList); }
    public Object[] getBlockedSuspendedSnapshot() { return snapshot(blockedSuspendedList); }

    /** Cantidad de suspensiones (escrituras al swap) decididas por el planificador a mediano plazo. */
    public int getSuspensionCount() {
        lock.acquireUninterruptibly();
        try {
            return suspensionCount;
        } finally {
            lock.release();
        }
    }

    /** Cantidad de reactivaciones (lecturas desde el swap). */
    public int getReactivationCount() {
        lock.acquireUninterruptibly();
        try {
            return reactivationCount;
        } finally {
            lock.release();
        }
    }
    public Object[] getTerminatedSnapshot() { return snapshot(terminatedList); }

    public Object[] getHostedBuffersSnapshot() {
        lock.acquireUninterruptibly();
        try {
            return hostedBuffers.toArray();
        } finally {
            lock.release();
        }
    }

    private Object[] snapshot(CustomQueue<ProcessControlBlock> queue) {
        lock.acquireUninterruptibly();
        try {
            return queue.toArray();
        } finally {
            lock.release();
        }
    }

    private Object[] snapshot(CustomLinkedList<ProcessControlBlock> list) {
        lock.acquireUninterruptibly();
        try {
            return list.toArray();
        } finally {
            lock.release();
        }
    }

    public int getComputerId()                   { return computerId; }
    public RAM getRam()                          { return ram; }
    public CPU getCpu()                          { return cpu; }
    public IODevice getDma()                     { return dma; }
    public IODevice getNetworkDevice()           { return network; }
    public int getCompletedCount()               { return completedCount; }
    public int getDeadlineMissedCount()          { return deadlineMissedCount; }
    public int getCurrentCycle()                 { return currentCycle; }
    public SchedulingPolicy getSchedulingPolicy(){ return policy; }
    public int getQuantum()                      { return quantum; }
}