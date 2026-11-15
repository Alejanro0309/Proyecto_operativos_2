package com.avilaos.tests;

import com.avilaos.disk.BlockInfo;
import com.avilaos.disk.BlockMap;
import com.avilaos.enums.BlockOwnerType;

// Prueba del mapa de bloques: asignación encadenada, liberación, zona de intercambio y el
// disco del enunciado (los 9 archivos del JSON ocupan los 200 bloques).
public class BlockMapTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testAllocation();
        testFreeAndReuse();
        testFragmentation();
        testAssignAndErrors();
        testSwapArea();
        testEnunciadoDisk();
        System.out.println("--------------------------------");
        System.out.println("Pruebas OK: " + passed + " | Fallidas: " + failed);
    }

    private static void testAllocation() {
        BlockMap map = new BlockMap(10, 0);
        check("mapa nuevo: todo libre", map.freeFileBlocks() == 10);
        int first = map.allocateFile("a.txt", 3);
        check("el primer archivo empieza en el bloque 0", first == 0);
        check("la cadena es 0 -> 1 -> 2", same(map.chainOf(0), 0, 1, 2));
        check("cada bloque apunta al siguiente y el último a -1",
                map.snapshot()[0].next() == 1 && map.snapshot()[1].next() == 2 && map.snapshot()[2].next() == -1);
        check("quedan 7 libres", map.freeFileBlocks() == 7);
        check("los bloques guardan el nombre del dueño", map.snapshot()[1].owner().equals("a.txt")
                && map.snapshot()[1].type() == BlockOwnerType.FILE);
        int before = map.freeFileBlocks();
        check("pedir más bloques de los libres devuelve -1", map.allocateFile("grande", 8) == -1);
        check("y no cambia el mapa", map.freeFileBlocks() == before);
        check("pedir 0 bloques se rechaza", throwsIAE(() -> map.allocateFile("x", 0)));
        check("un dueño vacío se rechaza", throwsIAE(() -> map.allocateFile("  ", 1)));
    }

    private static void testFreeAndReuse() {
        BlockMap map = new BlockMap(10, 0);
        map.allocateFile("a", 3);
        map.allocateFile("b", 4);
        check("liberar 'a' devuelve 3 bloques", map.free(0) == 3);
        check("los bloques de 'a' quedan libres", map.snapshot()[0].type() == BlockOwnerType.FREE
                && map.chainOf(0).length == 0);
        check("los de 'b' siguen ocupados", map.snapshot()[3].type() == BlockOwnerType.FILE);
        check("un archivo nuevo reutiliza el hueco", map.allocateFile("c", 2) == 0);
        check("liberar un bloque libre se rechaza", throwsIAE(() -> map.free(9)));
        check("liberar un bloque que no existe se rechaza", throwsIAE(() -> map.free(99)));
    }

    // Los bloques de un archivo no tienen que ser contiguos: la cadena salta de hueco en hueco
    private static void testFragmentation() {
        BlockMap map = new BlockMap(12, 0);
        map.allocateFile("a", 2);   // 0,1
        map.allocateFile("b", 3);   // 2,3,4
        map.allocateFile("c", 2);   // 5,6
        map.allocateFile("d", 5);   // 7..11
        map.free(0);                // hueco 0,1
        map.free(5);                // hueco 5,6
        int start = map.allocateFile("grande", 4);
        check("un archivo de 4 usa los huecos 0,1,5,6", start == 0);
        check("la cadena salta del 1 al 5", same(map.chainOf(0), 0, 1, 5, 6));
        check("el bloque 1 apunta al 5", map.snapshot()[1].next() == 5);
    }

    private static void testAssignAndErrors() {
        BlockMap map = new BlockMap(20, 0);
        map.assign(BlockOwnerType.FILE, "boot.bin", 5, 3);
        check("assign ocupa bloques consecutivos encadenados", same(map.chainOf(5), 5, 6, 7));
        check("assign sobre bloques ocupados se rechaza",
                throwsIAE(() -> map.assign(BlockOwnerType.FILE, "otro", 7, 2)));
        check("el rechazo no deja bloques a medias", map.snapshot()[8].type() == BlockOwnerType.FREE);
        check("assign fuera de la zona se rechaza",
                throwsIAE(() -> map.assign(BlockOwnerType.FILE, "x", 19, 5)));
        check("assign con tipo FREE se rechaza",
                throwsIAE(() -> map.assign(BlockOwnerType.FREE, "x", 0, 1)));
        check("el constructor rechaza 0 bloques", throwsIAE(() -> new BlockMap(0, 0)));
    }

    private static void testSwapArea() {
        BlockMap map = new BlockMap(10, 6);
        check("la zona de intercambio empieza después de la de archivos", map.getTotalBlocks() == 16
                && map.getSwapAreaBlocks() == 6);
        int swap = map.allocateSwap("P7", 4);
        check("el swap empieza en el bloque 10", swap == 10);
        check("los bloques del swap son de tipo SWAP", map.snapshot()[10].type() == BlockOwnerType.SWAP);
        check("las zonas se reservan por separado", map.freeFileBlocks() == 10 && map.freeSwapBlocks() == 2);
        check("sin espacio en el swap devuelve -1", map.allocateSwap("P8", 3) == -1);
        check("liberar el swap devuelve los bloques", map.free(10) == 4 && map.freeSwapBlocks() == 6);
        check("un archivo nunca invade el swap", map.allocateFile("grande", 11) == -1);
    }

    // El disco del JSON del enunciado: 9 archivos que suman los 200 bloques
    private static void testEnunciadoDisk() {
        BlockMap map = new BlockMap(200, 100);
        map.assign(BlockOwnerType.FILE, "/mbr.bin", 0, 11);
        map.assign(BlockOwnerType.FILE, "/sistema/boot.bin", 11, 23);
        map.assign(BlockOwnerType.FILE, "/usuarios/notas.txt", 34, 28);
        map.assign(BlockOwnerType.FILE, "/sistema/script.py", 62, 2);
        map.assign(BlockOwnerType.FILE, "/sistema/core.dll", 64, 31);
        map.assign(BlockOwnerType.FILE, "/sistema/config.sys", 95, 24);
        map.assign(BlockOwnerType.FILE, "/usuarios/img1.png", 119, 4);
        map.assign(BlockOwnerType.FILE, "/usuarios/data.csv", 123, 57);
        map.assign(BlockOwnerType.FILE, "/usuarios/video.mp4", 180, 20);
        check("los 9 archivos del enunciado llenan la zona de archivos (0 libres)", map.freeFileBlocks() == 0);
        check("con el disco lleno no se puede crear un archivo", map.allocateFile("nuevo", 1) == -1);
        check("el swap sigue libre y aparte", map.freeSwapBlocks() == 100);
        check("img1.png ocupa los bloques 119 a 122", same(map.chainOf(119), 119, 120, 121, 122));
        check("borrar img1.png libera 4 bloques", map.free(119) == 4 && map.freeFileBlocks() == 4);
        check("ahora sí cabe un archivo de 4 bloques, en el hueco", map.allocateFile("nuevo", 4) == 119);
        BlockInfo last = map.snapshot()[199];
        check("el último bloque es de video.mp4", last.owner().equals("/usuarios/video.mp4") && last.next() == -1);
    }

    // ---------------------------------------------------------------- auxiliares

    private static boolean same(int[] actual, int... expected) {
        if (actual.length != expected.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean throwsIAE(Runnable action) {
        try {
            action.run();
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
