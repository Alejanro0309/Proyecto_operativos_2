package com.avilaos.disk;

import com.avilaos.datastructures.CustomLinkedList;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.HeadDirection;

// SCAN (ascensor): atiende las solicitudes en la dirección actual, de la más cercana a la más lejana.
// Cuando ya no hay más en esa dirección, el cabezal llega hasta el extremo del disco (el recorrido
// cuenta) y regresa atendiendo en sentido contrario.
public class ScanDiskScheduler implements DiskSchedulingAlgorithm {

    @Override
    public DiskPolicy getPolicy() {
        return DiskPolicy.SCAN;
    }

    @Override
    public int selectNextIndex(CustomLinkedList<DiskRequest> queue, int head, HeadDirection direction) {
        if (queue.isEmpty()) {
            return -1;
        }
        int next = nearestInDirection(queue, head, direction);
        // nada más en esta dirección: se invierte y se atiende desde el lado contrario
        return next != -1 ? next : nearestInDirection(queue, head, direction.opposite());
    }

    // La solicitud más cercana al cabezal que está en la dirección indicada (o en el mismo bloque).
    private int nearestInDirection(CustomLinkedList<DiskRequest> queue, int head, HeadDirection direction) {
        int best = -1;
        for (int i = 0; i < queue.getSize(); i++) {
            int block = queue.get(i).getBlock();
            boolean inDirection = direction == HeadDirection.UP ? block >= head : block <= head;
            if (inDirection && (best == -1 || Math.abs(block - head) < Math.abs(queue.get(best).getBlock() - head))) {
                best = i;
            }
        }
        return best;
    }

    @Override
    public int travelDistance(int from, int to, HeadDirection direction, int totalBlocks) {
        int end = totalBlocks - 1;
        if (direction == HeadDirection.UP) {
            // si 'to' quedó atrás: sube hasta el extremo y baja hasta 'to'
            return to >= from ? to - from : (end - from) + (end - to);
        }
        // si 'to' quedó atrás: baja hasta 0 y sube hasta 'to'
        return to <= from ? from - to : from + to;
    }

    @Override
    public HeadDirection directionAfter(int from, int to, HeadDirection direction) {
        boolean reversed = direction == HeadDirection.UP ? to < from : to > from;
        return reversed ? direction.opposite() : direction;
    }
}
