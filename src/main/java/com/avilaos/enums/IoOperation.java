package com.avilaos.enums;

// Operaciones de E/S que un proceso puede pedirle al disco.
// CREATE, READ, UPDATE y DELETE son las del sistema de archivos (el CRUD del enunciado).
// SUSPEND y REACTIVATE las genera el planificador a mediano plazo para mover un proceso
// al área de intercambio y traerlo de vuelta. Las seis pasan por la misma cola del disco.
public enum IoOperation {
    CREATE,      // crea un archivo o directorio (reserva bloques si es archivo)
    READ,        // lee un archivo
    UPDATE,      // cambia el nombre de un archivo o directorio
    DELETE,      // elimina el archivo o directorio y libera sus bloques
    SUSPEND,     // escribe un proceso en el área de intercambio
    REACTIVATE;  // trae un proceso del área de intercambio de vuelta a la RAM

    // true para las operaciones sobre archivos que pide el usuario; false para las de swap
    public boolean isFileOperation() {
        return this == CREATE || this == READ || this == UPDATE || this == DELETE;
    }
}