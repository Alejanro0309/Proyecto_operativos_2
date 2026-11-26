package com.avilaos.disk;

// Lo único que el kernel necesita del disco: dejar una solicitud en su cola.
// El kernel recibe un DiskPort por un setter y no conoce la clase Disk (mismo patrón que WakeUpRouter).
public interface DiskPort {

    // Deja la solicitud en la cola de E/S. Se empieza a considerar en el ciclo siguiente al de llegada.
    void submitRequest(DiskRequest request);

    // Retira de la cola la solicitud de ese proceso (por ejemplo, si venció su deadline).
    // Si el disco ya la está atendiendo, termina igual y el kernel descarta el aviso.
    // Devuelve true si estaba en la cola.
    boolean cancelRequest(int processId);
}
