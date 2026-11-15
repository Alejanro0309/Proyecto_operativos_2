package com.avilaos.disk;

import com.avilaos.enums.BlockOwnerType;
import java.util.concurrent.Semaphore;

// Mapa de bloques del disco con asignación encadenada: cada archivo es una lista enlazada de bloques
// (cada DiskBlock apunta al siguiente), y los bloques de un archivo no tienen que ser contiguos.
//
// El mapa tiene dos zonas: la de archivos (bloques 0 .. fileAreaBlocks-1) y la de intercambio (swap),
// que va a continuación (fileAreaBlocks .. total-1). Las dos se reservan por separado.
// Un Semaphore protege el mapa: lo usan el hilo del disco y el de la interfaz.
public class BlockMap {

    private final DiskBlock[] blocks;
    private final int fileAreaBlocks;
    private final Semaphore lock = new Semaphore(1);

    public BlockMap(int fileAreaBlocks, int swapAreaBlocks) {
        if (fileAreaBlocks <= 0) {
            throw new IllegalArgumentException("La zona de archivos debe tener al menos 1 bloque");
        }
        if (swapAreaBlocks < 0) {
            throw new IllegalArgumentException("La zona de intercambio no puede ser negativa");
        }
        this.fileAreaBlocks = fileAreaBlocks;
        this.blocks = new DiskBlock[fileAreaBlocks + swapAreaBlocks];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = new DiskBlock();
        }
    }

    // ---------------------------------------------------------------- reservar

    // Reserva 'count' bloques libres de la zona de archivos y los encadena (primer ajuste:
    // los primeros libres de menor a mayor número). Devuelve el primer bloque, o -1 si no
    // alcanzan los bloques libres; en ese caso el mapa no cambia.
    public int allocateFile(String owner, int count) {
        return allocate(BlockOwnerType.FILE, owner, count, 0, fileAreaBlocks);
    }

    // Igual, en la zona de intercambio (para los procesos suspendidos).
    public int allocateSwap(String owner, int count) {
        return allocate(BlockOwnerType.SWAP, owner, count, fileAreaBlocks, blocks.length);
    }

    private int allocate(BlockOwnerType type, String owner, int count, int from, int to) {
        if (count <= 0) {
            throw new IllegalArgumentException("La cantidad de bloques debe ser mayor que 0");
        }
        if (owner == null || owner.trim().isEmpty()) {
            throw new IllegalArgumentException("El dueño del bloque no puede estar vacío");
        }
        lock.acquireUninterruptibly();
        try {
            int free = 0;
            for (int i = from; i < to; i++) {
                if (blocks[i].isFree()) {
                    free++;
                }
            }
            if (free < count) {
                return -1;
            }
            int first = -1;
            int previous = -1;
            int taken = 0;
            for (int i = from; i < to && taken < count; i++) {
                if (blocks[i].isFree()) {
                    blocks[i].assign(type, owner);
                    if (previous == -1) {
                        first = i;
                    } else {
                        blocks[previous].setNext(i);
                    }
                    previous = i;
                    taken++;
                }
            }
            return first;
        } finally {
            lock.release();
        }
    }

    // Ocupa 'count' bloques consecutivos desde 'startBlock'. Sirve para cargar el estado del JSON,
    // que trae el primer bloque y la cantidad. Lanza IllegalArgumentException si algo no cabe o
    // ya está ocupado, y en ese caso no cambia nada.
    public void assign(BlockOwnerType type, String owner, int startBlock, int count) {
        if (type == null || type == BlockOwnerType.FREE) {
            throw new IllegalArgumentException("El tipo debe ser FILE o SWAP");
        }
        if (owner == null || owner.trim().isEmpty()) {
            throw new IllegalArgumentException("El dueño del bloque no puede estar vacío");
        }
        int from = type == BlockOwnerType.FILE ? 0 : fileAreaBlocks;
        int to = type == BlockOwnerType.FILE ? fileAreaBlocks : blocks.length;
        if (count <= 0 || startBlock < from || startBlock + count > to) {
            throw new IllegalArgumentException("'" + owner + "': los bloques " + startBlock + " a "
                    + (startBlock + count - 1) + " no caben en su zona (" + from + " a " + (to - 1) + ")");
        }
        lock.acquireUninterruptibly();
        try {
            for (int i = startBlock; i < startBlock + count; i++) {
                if (!blocks[i].isFree()) {
                    throw new IllegalArgumentException("'" + owner + "': el bloque " + i
                            + " ya está ocupado por '" + blocks[i].getOwner() + "'");
                }
            }
            for (int i = startBlock; i < startBlock + count; i++) {
                blocks[i].assign(type, owner);
                if (i > startBlock) {
                    blocks[i - 1].setNext(i);
                }
            }
        } finally {
            lock.release();
        }
    }

    // ---------------------------------------------------------------- liberar

    // Libera toda la cadena que empieza en 'startBlock' y devuelve cuántos bloques liberó.
    public int free(int startBlock) {
        if (startBlock < 0 || startBlock >= blocks.length) {
            throw new IllegalArgumentException("El bloque " + startBlock + " no existe");
        }
        lock.acquireUninterruptibly();
        try {
            if (blocks[startBlock].isFree()) {
                throw new IllegalArgumentException("El bloque " + startBlock + " ya está libre");
            }
            int freed = 0;
            int current = startBlock;
            while (current != -1 && freed <= blocks.length) {
                int next = blocks[current].getNext();
                blocks[current].release();
                freed++;
                current = next;
            }
            return freed;
        } finally {
            lock.release();
        }
    }

    // ---------------------------------------------------------------- consultas

    // Los bloques de la cadena que empieza en 'startBlock', en orden. Sirve para guardar el estado
    // en el JSON y para pintar el archivo en el mapa.
    public int[] chainOf(int startBlock) {
        if (startBlock < 0 || startBlock >= blocks.length) {
            throw new IllegalArgumentException("El bloque " + startBlock + " no existe");
        }
        lock.acquireUninterruptibly();
        try {
            if (blocks[startBlock].isFree()) {
                return new int[0];
            }
            int length = 0;
            for (int b = startBlock; b != -1 && length <= blocks.length; b = blocks[b].getNext()) {
                length++;
            }
            int[] chain = new int[length];
            int b = startBlock;
            for (int i = 0; i < length; i++) {
                chain[i] = b;
                b = blocks[b].getNext();
            }
            return chain;
        } finally {
            lock.release();
        }
    }

    public int freeFileBlocks() {
        return countFree(0, fileAreaBlocks);
    }

    public int freeSwapBlocks() {
        return countFree(fileAreaBlocks, blocks.length);
    }

    private int countFree(int from, int to) {
        lock.acquireUninterruptibly();
        try {
            int free = 0;
            for (int i = from; i < to; i++) {
                if (blocks[i].isFree()) {
                    free++;
                }
            }
            return free;
        } finally {
            lock.release();
        }
    }

    // Copia de todo el mapa, para la interfaz.
    public BlockInfo[] snapshot() {
        lock.acquireUninterruptibly();
        try {
            BlockInfo[] copy = new BlockInfo[blocks.length];
            for (int i = 0; i < blocks.length; i++) {
                copy[i] = new BlockInfo(blocks[i].getOwnerType(), blocks[i].getOwner(), blocks[i].getNext());
            }
            return copy;
        } finally {
            lock.release();
        }
    }

    public int getFileAreaBlocks() { return fileAreaBlocks; }
    public int getSwapAreaBlocks() { return blocks.length - fileAreaBlocks; }
    public int getTotalBlocks() { return blocks.length; }
}
