package com.avilaos.hardware;

import com.avilaos.datastructures.CustomLinkedList;
import com.avilaos.disk.DiskCompletionListener;
import com.avilaos.disk.DiskPort;
import com.avilaos.disk.DiskRequest;
import com.avilaos.disk.DiskSchedulerFactory;
import com.avilaos.disk.DiskSchedulingAlgorithm;
import com.avilaos.disk.DiskStatus;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.HeadDirection;
import java.util.concurrent.Semaphore;

// Disco: un componente de hardware que obedece al reloj (ClockListener). En cada ciclo avanza
// exactamente un ciclo de trabajo, y atiende UNA solicitud a la vez.
//
// Atender una solicitud tiene dos fases:
//   1. búsqueda: el cabezal recorre el trayecto a 'headSpeed' posiciones por ciclo;
//   2. transferencia: cada bloque leído o escrito cuesta un ciclo.
// La política (DiskSchedulingAlgorithm) decide a quién atender y cuánto recorre el cabezal.
//
// Determinismo: una solicitud solo entra a competir en el ciclo SIGUIENTE al de su llegada. Así el
// resultado no depende de en qué orden corran los hilos del kernel y del disco dentro de un ciclo.
// Un Semaphore protege la cola y el estado (los usan el hilo del disco, el del kernel y la interfaz).
public class Disk implements ClockListener, DiskPort {

    private final int totalBlocks;     // tamaño de la zona de archivos: el último bloque es totalBlocks - 1
    private final int headSpeed;       // posiciones que recorre el cabezal por ciclo
    private final Semaphore lock = new Semaphore(1);

    private final CustomLinkedList<DiskRequest> arrivals = new CustomLinkedList<>();  // llegaron, aún no compiten
    private final CustomLinkedList<DiskRequest> queue = new CustomLinkedList<>();     // la cola de E/S

    private DiskPolicy policy;
    private DiskSchedulingAlgorithm scheduler;
    private int head;
    private HeadDirection direction = HeadDirection.UP;

    // solicitud en curso
    private DiskRequest current;
    private DiskPolicy currentPolicy;
    private int seekFrom;
    private int seekTo;
    private int seekTotal;
    private int seekDone;
    private HeadDirection directionAfterSeek;
    private int transferRemaining;

    // contadores para las métricas
    private int totalDisplacement;
    private int served;
    private long totalWait;
    private int busyCycles;
    private int idleCycles;
    private final int[] servedPerPolicy = new int[DiskPolicy.values().length];

    private volatile DiskCompletionListener completionListener;

    public Disk(int totalBlocks, int initialHead, int headSpeed, DiskPolicy initialPolicy) {
        if (totalBlocks <= 0) {
            throw new IllegalArgumentException("El disco debe tener al menos 1 bloque");
        }
        if (initialHead < 0 || initialHead >= totalBlocks) {
            throw new IllegalArgumentException("El cabezal inicial debe estar entre 0 y " + (totalBlocks - 1));
        }
        if (headSpeed < 1) {
            throw new IllegalArgumentException("El cabezal debe recorrer al menos 1 posición por ciclo");
        }
        this.totalBlocks = totalBlocks;
        this.head = initialHead;
        this.headSpeed = headSpeed;
        this.policy = initialPolicy;
        this.scheduler = DiskSchedulerFactory.create(initialPolicy);
    }

    // ---------------------------------------------------------------- órdenes

    @Override
    public void submitRequest(DiskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("La solicitud es obligatoria");
        }
        if (request.getBlock() >= totalBlocks) {
            throw new IllegalArgumentException("El bloque " + request.getBlock() + " no existe (el disco llega hasta el "
                    + (totalBlocks - 1) + ")");
        }
        lock.acquireUninterruptibly();
        try {
            arrivals.add(request);
        } finally {
            lock.release();
        }
    }

    @Override
    public boolean cancelRequest(int processId) {
        lock.acquireUninterruptibly();
        try {
            return removeByProcess(arrivals, processId) || removeByProcess(queue, processId);
        } finally {
            lock.release();
        }
    }

    private static boolean removeByProcess(CustomLinkedList<DiskRequest> list, int processId) {
        for (int i = 0; i < list.getSize(); i++) {
            if (list.get(i).getProcessId() == processId) {
                list.removeAt(i);
                return true;
            }
        }
        return false;
    }

    // Cambia la política en ejecución. Vale desde la próxima selección: la solicitud en curso no se toca.
    public void setPolicy(DiskPolicy newPolicy) {
        DiskSchedulingAlgorithm newScheduler = DiskSchedulerFactory.create(newPolicy);
        lock.acquireUninterruptibly();
        try {
            this.policy = newPolicy;
            this.scheduler = newScheduler;
        } finally {
            lock.release();
        }
    }

    public void setCompletionListener(DiskCompletionListener listener) {
        this.completionListener = listener;
    }

    // ---------------------------------------------------------------- un ciclo del reloj

    @Override
    public void onTick(int cycle) {
        DiskRequest completed = null;
        lock.acquireUninterruptibly();
        try {
            admitArrivals(cycle);
            if (current == null) {
                startNext(cycle);
            }
            if (current == null) {
                idleCycles++;
            } else {
                busyCycles++;
                completed = work();
            }
        } finally {
            lock.release();
        }
        // el aviso va fuera del lock: quien lo recibe puede volver a llamar a submitRequest
        DiskCompletionListener listener = completionListener;
        if (completed != null && listener != null) {
            listener.onRequestCompleted(completed, cycle);
        }
    }

    // Pasa a la cola las solicitudes que llegaron en ciclos anteriores
    private void admitArrivals(int cycle) {
        while (!arrivals.isEmpty() && arrivals.get(0).getArrivalCycle() < cycle) {
            queue.add(arrivals.removeAt(0));
        }
    }

    // Elige la siguiente solicitud con la política y prepara el trayecto del cabezal
    private void startNext(int cycle) {
        int index = scheduler.selectNextIndex(queue, head, direction);
        if (index == -1) {
            return;
        }
        current = queue.removeAt(index);
        currentPolicy = policy;
        totalWait += cycle - current.getArrivalCycle();
        seekFrom = head;
        seekTo = current.getBlock();
        seekTotal = scheduler.travelDistance(head, seekTo, direction, totalBlocks);
        seekDone = 0;
        directionAfterSeek = scheduler.directionAfter(head, seekTo, direction);
        transferRemaining = current.getBlockCount();
        if (seekTotal == 0) {
            head = seekTo;
            direction = directionAfterSeek;
        }
    }

    // Un ciclo de trabajo sobre la solicitud en curso. Devuelve la solicitud si terminó.
    private DiskRequest work() {
        if (seekDone < seekTotal) {
            int step = Math.min(headSpeed, seekTotal - seekDone);
            seekDone += step;
            totalDisplacement += step;
            // la posición se interpola entre el origen y el destino (solo afecta a lo que se ve);
            // el desplazamiento acumulado sí cuenta el recorrido completo, también el de SCAN y C-SCAN
            head = seekDone == seekTotal ? seekTo
                    : seekFrom + (int) Math.round((double) (seekTo - seekFrom) * seekDone / seekTotal);
            if (seekDone == seekTotal) {
                direction = directionAfterSeek;
            }
        } else {
            transferRemaining--;
        }
        if (seekDone == seekTotal && transferRemaining == 0) {
            DiskRequest finished = current;
            current = null;
            served++;
            servedPerPolicy[currentPolicy.ordinal()]++;
            return finished;
        }
        return null;
    }

    // ---------------------------------------------------------------- lectura para la interfaz y las métricas

    public DiskStatus snapshot() {
        lock.acquireUninterruptibly();
        try {
            DiskRequest[] waiting = new DiskRequest[queue.getSize() + arrivals.getSize()];
            int pos = 0;
            for (int i = 0; i < queue.getSize(); i++) {
                waiting[pos++] = queue.get(i);
            }
            for (int i = 0; i < arrivals.getSize(); i++) {
                waiting[pos++] = arrivals.get(i);
            }
            int[] perPolicy = new int[servedPerPolicy.length];
            for (int i = 0; i < perPolicy.length; i++) {
                perPolicy[i] = servedPerPolicy[i];
            }
            return new DiskStatus(policy, head, direction, totalBlocks, current != null, current, waiting,
                    totalDisplacement, served, served == 0 ? Double.NaN : (double) totalWait / served,
                    perPolicy, busyCycles, idleCycles);
        } finally {
            lock.release();
        }
    }

    public int getTotalBlocks() { return totalBlocks; }
    public int getHeadSpeed() { return headSpeed; }
}