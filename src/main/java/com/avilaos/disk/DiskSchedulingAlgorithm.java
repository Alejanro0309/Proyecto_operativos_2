package com.avilaos.disk;

import com.avilaos.datastructures.CustomLinkedList;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.HeadDirection;

// Política de planificación del disco. Agregar una política nueva es implementar esta interfaz
// y registrarla en DiskSchedulerFactory: ni Disk ni el kernel cambian.
//
// La política decide dos cosas: a quién atender (selectNextIndex) y cuánto recorre el cabezal para
// llegar (travelDistance). Lo segundo es parte de la política porque SCAN y C-SCAN recorren hasta el
// extremo del disco antes de volver. Las implementaciones no guardan estado: la posición y la
// dirección del cabezal las lleva Disk y se las pasa en cada llamada.
public interface DiskSchedulingAlgorithm {

    DiskPolicy getPolicy();

    // Índice (en la cola) de la solicitud que se atiende ahora, o -1 si la cola está vacía.
    int selectNextIndex(CustomLinkedList<DiskRequest> queue, int head, HeadDirection direction);

    // Posiciones que recorre el cabezal para ir de 'from' a 'to'. totalBlocks es el tamaño de la
    // zona de archivos (el último bloque es totalBlocks - 1). En C-SCAN el salto de regreso no cuenta.
    int travelDistance(int from, int to, HeadDirection direction, int totalBlocks);

    // Dirección en que queda el cabezal después de atender la solicitud en 'to'.
    HeadDirection directionAfter(int from, int to, HeadDirection direction);
}
