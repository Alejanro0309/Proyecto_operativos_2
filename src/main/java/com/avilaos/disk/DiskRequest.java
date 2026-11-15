package com.avilaos.disk;

import com.avilaos.enums.IoOperation;

// Solicitud de E/S que un proceso deja en la cola del disco. Es inmutable.
// Guarda el id del proceso y no el proceso mismo: así el disco no depende del modelo de procesos.
// Cuando el disco termina, devuelve la solicitud y el kernel despierta al proceso con ese id.
public class DiskRequest {

    private final int block;          // bloque donde está el archivo (posición del cabezal que se necesita)
    private final IoOperation operation;
    private final int processId;
    private final int arrivalCycle;   // ciclo en que entró a la cola, para medir la espera

    public DiskRequest(int block, IoOperation operation, int processId, int arrivalCycle) {
        if (block < 0) {
            throw new IllegalArgumentException("El bloque no puede ser negativo");
        }
        if (operation == null) {
            throw new IllegalArgumentException("La operación es obligatoria");
        }
        if (arrivalCycle < 0) {
            throw new IllegalArgumentException("El ciclo de llegada no puede ser negativo");
        }
        this.block = block;
        this.operation = operation;
        this.processId = processId;
        this.arrivalCycle = arrivalCycle;
    }

    public int getBlock() { return block; }
    public IoOperation getOperation() { return operation; }
    public int getProcessId() { return processId; }
    public int getArrivalCycle() { return arrivalCycle; }

    @Override
    public String toString() {
        return "P" + processId + " " + operation + " @bloque " + block;
    }
}
