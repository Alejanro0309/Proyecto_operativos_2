package com.avilaos.tests;

import com.avilaos.datastructures.CustomLinkedList;
import com.avilaos.disk.DiskRequest;
import com.avilaos.disk.DiskSchedulerFactory;
import com.avilaos.disk.DiskSchedulingAlgorithm;
import com.avilaos.enums.DiskPolicy;
import com.avilaos.enums.HeadDirection;
import com.avilaos.enums.IoOperation;

// Prueba de las cuatro políticas de disco con el anexo y los casos P1 a P4 del enunciado.
// Simula lo que hará Disk: elige, mide el recorrido, actualiza dirección y posición del cabezal.
public class DiskSchedulingTest {

    private static final int TOTAL_BLOCKS = 200;
    private static final int HEAD = 50;

    private static int passed = 0;
    private static int failed = 0;

    private record Result(String order, int total) {
    }

    public static void main(String[] args) {
        int[] annex = {82, 17, 43, 140, 24, 16, 190};
        int[] cases = {95, 180, 34, 119, 11, 123, 62, 64};

        // Anexo del enunciado (totales dados por el enunciado)
        verify("Anexo FIFO", run(DiskPolicy.FIFO, annex), "82,17,43,140,24,16,190", 518);
        verify("Anexo SSTF", run(DiskPolicy.SSTF, annex), "43,24,17,16,82,140,190", 208);
        verify("Anexo SCAN", run(DiskPolicy.SCAN, annex), "82,140,190,43,24,17,16", 332);
        verify("Anexo C-SCAN", run(DiskPolicy.C_SCAN, annex), "82,140,190,16,17,24,43", 192);

        // Casos P1 a P4: el orden lo da el enunciado; los totales son cálculo propio
        verify("P1 FIFO", run(DiskPolicy.FIFO, cases), "95,180,34,119,11,123,62,64", 644);
        verify("P2 SSTF", run(DiskPolicy.SSTF, cases), "62,64,34,11,95,119,123,180", 236);
        verify("P3 SCAN", run(DiskPolicy.SCAN, cases), "62,64,95,119,123,180,34,11", 337);
        verify("P4 C-SCAN", run(DiskPolicy.C_SCAN, cases), "62,64,95,119,123,180,11,34", 183);

        // Casos borde
        for (DiskPolicy policy : DiskPolicy.values()) {
            DiskSchedulingAlgorithm scheduler = DiskSchedulerFactory.create(policy);
            check(policy + ": la fábrica devuelve la política pedida", scheduler.getPolicy() == policy);
            check(policy + ": cola vacía devuelve -1",
                    scheduler.selectNextIndex(new CustomLinkedList<DiskRequest>(), HEAD, HeadDirection.UP) == -1);
            check(policy + ": una sola solicitud en el bloque del cabezal cuesta 0",
                    run(policy, new int[]{HEAD}).total() == 0);
        }
        check("SSTF: en un empate de distancia gana la que llegó primero",
                run(DiskPolicy.SSTF, new int[]{40, 60}).order().equals("40,60"));
        check("la fábrica rechaza una política nula", rejectsNullPolicy());

        System.out.println("--------------------------------");
        System.out.println("Pruebas OK: " + passed + " | Fallidas: " + failed);
    }

    // Atiende toda la cola con la política y devuelve el orden y el recorrido total
    private static Result run(DiskPolicy policy, int[] blocks) {
        DiskSchedulingAlgorithm scheduler = DiskSchedulerFactory.create(policy);
        CustomLinkedList<DiskRequest> queue = new CustomLinkedList<>();
        for (int i = 0; i < blocks.length; i++) {
            queue.add(new DiskRequest(blocks[i], IoOperation.READ, i + 1, 0));
        }
        int head = HEAD;
        HeadDirection direction = HeadDirection.UP;
        int total = 0;
        StringBuilder order = new StringBuilder();
        while (!queue.isEmpty()) {
            int index = scheduler.selectNextIndex(queue, head, direction);
            DiskRequest request = queue.removeAt(index);
            total += scheduler.travelDistance(head, request.getBlock(), direction, TOTAL_BLOCKS);
            direction = scheduler.directionAfter(head, request.getBlock(), direction);
            head = request.getBlock();
            order.append(order.length() == 0 ? "" : ",").append(head);
        }
        return new Result(order.toString(), total);
    }

    private static void verify(String name, Result result, String expectedOrder, int expectedTotal) {
        check(name + ": orden " + expectedOrder, result.order().equals(expectedOrder));
        check(name + ": recorrido total " + expectedTotal + " (obtenido " + result.total() + ")",
                result.total() == expectedTotal);
    }

    private static boolean rejectsNullPolicy() {
        try {
            DiskSchedulerFactory.create(null);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("[OK]    " + name);
        } else {
            failed++;
            System.out.println("[FALLA] " + name);
        }
    }
}
