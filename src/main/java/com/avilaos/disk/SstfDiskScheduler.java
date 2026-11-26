package com.avilaos.disk;

import com.avilaos.datastructures.CustomLinkedList;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.HeadDirection;

// SSTF: atiende la solicitud más cercana al cabezal. Si dos están a la misma distancia,
// gana la que llegó primero. Puede dejar esperando mucho a las solicitudes lejanas (inanición).
public class SstfDiskScheduler implements DiskSchedulingAlgorithm {

    @Override
    public DiskPolicy getPolicy() {
        return DiskPolicy.SSTF;
    }

    @Override
    public int selectNextIndex(CustomLinkedList<DiskRequest> queue, int head, HeadDirection direction) {
        if (queue.isEmpty()) {
            return -1;
        }
        int best = 0;
        for (int i = 1; i < queue.getSize(); i++) {
            // estrictamente menor: en un empate se queda la que llegó antes
            if (Math.abs(queue.get(i).getBlock() - head) < Math.abs(queue.get(best).getBlock() - head)) {
                best = i;
            }
        }
        return best;
    }

    @Override
    public int travelDistance(int from, int to, HeadDirection direction, int totalBlocks) {
        return Math.abs(to - from);
    }

    @Override
    public HeadDirection directionAfter(int from, int to, HeadDirection direction) {
        return to > from ? HeadDirection.UP : to < from ? HeadDirection.DOWN : direction;
    }
}
