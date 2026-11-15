package com.avilaos.disk;

import com.avilaos.datastructures.CustomLinkedList;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.HeadDirection;

// C-SCAN: solo atiende hacia arriba. Al llegar al extremo, el cabezal salta al bloque 0 y sigue
// subiendo. El recorrido hasta el extremo cuenta, pero el salto de regreso no (como en el anexo
// del enunciado).
public class CScanDiskScheduler implements DiskSchedulingAlgorithm {

    @Override
    public DiskPolicy getPolicy() {
        return DiskPolicy.C_SCAN;
    }

    @Override
    public int selectNextIndex(CustomLinkedList<DiskRequest> queue, int head, HeadDirection direction) {
        if (queue.isEmpty()) {
            return -1;
        }
        // la más cercana por encima del cabezal
        int best = -1;
        for (int i = 0; i < queue.getSize(); i++) {
            int block = queue.get(i).getBlock();
            if (block >= head && (best == -1 || block < queue.get(best).getBlock())) {
                best = i;
            }
        }
        if (best != -1) {
            return best;
        }
        // ninguna por encima: tras el salto al 0, la de menor número de bloque
        best = 0;
        for (int i = 1; i < queue.getSize(); i++) {
            if (queue.get(i).getBlock() < queue.get(best).getBlock()) {
                best = i;
            }
        }
        return best;
    }

    @Override
    public int travelDistance(int from, int to, HeadDirection direction, int totalBlocks) {
        // si 'to' quedó atrás: sube hasta el extremo, salta al 0 (no cuenta) y sube hasta 'to'
        return to >= from ? to - from : (totalBlocks - 1 - from) + to;
    }

    @Override
    public HeadDirection directionAfter(int from, int to, HeadDirection direction) {
        return HeadDirection.UP;
    }
}
