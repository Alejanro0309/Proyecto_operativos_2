package com.avilaos.filesystem;

// Puerto entre la interfaz y el sistema (mismo patrón que DiskPort y WakeUpRouter): la interfaz
// pide una operación y el sistema crea el proceso de E/S correspondiente en el computador.
// Puede lanzar IllegalArgumentException si el sistema la rechaza; la interfaz muestra el mensaje.
public interface FileOperationRequester {

    void requestFileOperation(FileOperation operation);
}
