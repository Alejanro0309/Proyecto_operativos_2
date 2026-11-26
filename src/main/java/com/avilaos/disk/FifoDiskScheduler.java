package com.avilaos.disk;

import com.avilaos.datastructures.CustomLinkedList;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.HeadDirection;

// FIFO: atiende en orden de llegada, sin mirar dónde está el cabezal.
public class FifoDiskScheduler implements DiskSchedulingAlgorithm {

    @Override
    public DiskPolicy getPolicy() {
        return DiskPolicy.FIFO;
    }

    @Override
    public int selectNextIndex(CustomLinkedList<DiskRequest> queue, int head, HeadDirection direction) {
        return queue.isEmpty() ? -1 : 0;
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
