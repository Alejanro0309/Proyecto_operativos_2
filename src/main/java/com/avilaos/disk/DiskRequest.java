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
    private final int blockCount;     // NUEVO: cantidad de bloques a transferir (1 ciclo por bloque)

    
    // Constructor original de 4 parámetros (delega con 1 bloque por defecto)
    public DiskRequest(int block, IoOperation operation, int processId, int arrivalCycle) {
        this(block, operation, processId, arrivalCycle, 1);
    }

    // Constructor completo de 5 parámetros
    public DiskRequest(int block, IoOperation operation, int processId, int arrivalCycle, int blockCount) {
        if (block < 0) {
            throw new IllegalArgumentException("El bloque no puede ser negativo");
        }
        if (operation == null) {
            throw new IllegalArgumentException("La operación es obligatoria");
        }
        if (arrivalCycle < 0) {
            throw new IllegalArgumentException("El ciclo de llegada no puede ser negativo");
        }
        if (blockCount < 1) {
            throw new IllegalArgumentException("La cantidad de bloques debe ser al menos 1");
        }
        this.block = block;
        this.operation = operation;
        this.processId = processId;
        this.arrivalCycle = arrivalCycle;
        this.blockCount = blockCount;
    }

    public int getBlock() { return block; }
    public IoOperation getOperation() { return operation; }
    public int getProcessId() { return processId; }
    public int getArrivalCycle() { return arrivalCycle; }
    public int getBlockCount() { return blockCount; } // NUEVO: resuelve el error de la línea 159
    
    @Override
    public String toString() {
        return "P" + processId + " " + operation + " @bloque " + block;
    }

    }
  
