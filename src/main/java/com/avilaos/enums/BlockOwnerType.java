package com.avilaos.enums;

// Quién ocupa un bloque del disco. La interfaz usa este valor (y el nombre del dueño)
// para pintar el mapa de bloques.
public enum BlockOwnerType {
    FREE,   // libre
    FILE,   // lo ocupa un archivo
    SWAP    // lo ocupa un proceso suspendido, en el área de intercambio
}
