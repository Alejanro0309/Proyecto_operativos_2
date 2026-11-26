package com.avilaos.disk;

import com.avilaos.enums.BlockOwnerType;

// Un bloque del disco. En la asignación encadenada cada bloque guarda la referencia al siguiente
// bloque de su archivo (o -1 si es el último). Sin sincronización propia: BlockMap la protege.
class DiskBlock {

    private BlockOwnerType ownerType = BlockOwnerType.FREE;
    private String owner = "";
    private int next = -1;

    boolean isFree() { return ownerType == BlockOwnerType.FREE; }
    BlockOwnerType getOwnerType() { return ownerType; }
    String getOwner() { return owner; }
    int getNext() { return next; }

    void assign(BlockOwnerType type, String ownerName) {
        this.ownerType = type;
        this.owner = ownerName;
        this.next = -1;
    }

    void setNext(int next) {
        this.next = next;
    }

    void release() {
        this.ownerType = BlockOwnerType.FREE;
        this.owner = "";
        this.next = -1;
    }
}
