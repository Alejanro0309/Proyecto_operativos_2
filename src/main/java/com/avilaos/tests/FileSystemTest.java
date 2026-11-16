package com.avilaos.tests;

import com.avilaos.disk.BlockMap;
import com.avilaos.enums.FilePermission;
import com.avilaos.enums.FileType;
import com.avilaos.enums.UserMode;
import com.avilaos.filesystem.FileSystem;

// Prueba del sistema de archivos: el disco del enunciado, crear, eliminar, renombrar y los permisos
// por modo (administrador y usuario).
public class FileSystemTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testEnunciadoDisk();
        testCreateAndDelete();
        testDeleteDirectory();
        testRename();
        testPermissions();
        testInvalidAndErrors();
        System.out.println("--------------------------------");
        System.out.println("Pruebas OK: " + passed + " | Fallidas: " + failed);
    }

    // Reproduce el JSON de sistema de archivos del enunciado (9 archivos que llenan los 200 bloques)
    private static FileSystem enunciadoFs(BlockMap map) {
        FileSystem fs = new FileSystem(map);
        FilePermission pub = FilePermission.PUBLIC;
        FilePermission priv = FilePermission.PRIVATE;
        fs.loadFile("/mbr.bin", 0, 11, "admin", pub, "inicial");
        fs.createDirectory("/sistema", "admin", pub, "inicial");
        fs.createDirectory("/usuarios", "admin", pub, "inicial");
        fs.loadFile("/sistema/boot.bin", 11, 23, "admin", pub, "inicial");
        fs.loadFile("/usuarios/notas.txt", 34, 28, "user1", priv, "inicial");
        fs.loadFile("/sistema/script.py", 62, 2, "admin", pub, "inicial");
        fs.loadFile("/sistema/core.dll", 64, 31, "admin", pub, "inicial");
        fs.loadFile("/sistema/config.sys", 95, 24, "admin", pub, "inicial");
        fs.loadFile("/usuarios/img1.png", 119, 4, "user1", priv, "inicial");
        fs.loadFile("/usuarios/data.csv", 123, 57, "user2", pub, "inicial");
        fs.loadFile("/usuarios/video.mp4", 180, 20, "user1", pub, "inicial");
        return fs;
    }

    private static void testEnunciadoDisk() {
        BlockMap map = new BlockMap(200, 100);
        FileSystem fs = enunciadoFs(map);
        FileSystem.EntryInfo[] all = fs.snapshot();
        check("el árbol tiene 12 entradas (raíz, 2 directorios y 9 archivos)", all.length == 12);
        check("la primera entrada es la raíz", all[0].path().equals("/") && all[0].depth() == 0);
        check("después de la raíz viene /mbr.bin", all[1].path().equals("/mbr.bin") && all[1].depth() == 1);
        check("cada directorio aparece antes de lo que contiene",
                all[2].path().equals("/sistema") && all[3].path().equals("/sistema/boot.bin") && all[3].depth() == 2);
        FileSystem.EntryInfo boot = fs.info("/sistema/boot.bin");
        check("boot.bin: primer bloque 11, 23 bloques, dueño admin",
                boot.startBlock() == 11 && boot.blocks() == 23 && boot.owner().equals("admin"));
        check("notas.txt es privado de user1", fs.info("/usuarios/notas.txt").permission() == FilePermission.PRIVATE
                && fs.info("/usuarios/notas.txt").owner().equals("user1"));
        check("un directorio no ocupa bloques", fs.info("/sistema").startBlock() == -1 && fs.info("/sistema").blocks() == 0);
        check("el disco del enunciado está lleno (0 bloques libres)", fs.freeBlocks() == 0 && fs.firstFreeBlock() == -1);
        check("info de una ruta que no existe es null", fs.info("/nada") == null && !fs.exists("/nada"));
    }

    private static void testCreateAndDelete() {
        BlockMap map = new BlockMap(200, 0);
        FileSystem fs = enunciadoFs(map);
        check("con el disco lleno no se puede crear un archivo",
                fs.checkCreate("/usuarios/nuevo.txt", FileType.FILE, 3, UserMode.ADMINISTRATOR, null) != null);
        check("y aplicar la creación lanza el error", throwsIAE(
                () -> fs.createFile("/usuarios/nuevo.txt", 3, "admin", FilePermission.PUBLIC, "P1")));
        check("el intento fallido no deja un archivo a medias", !fs.exists("/usuarios/nuevo.txt"));

        check("eliminar img1.png libera 4 bloques", fs.delete("/usuarios/img1.png") == 4);
        check("img1.png ya no existe", !fs.exists("/usuarios/img1.png") && fs.freeBlocks() == 4);
        check("el primer bloque libre es el 119", fs.firstFreeBlock() == 119);
        check("ahora sí se puede crear un archivo de 4 bloques",
                fs.checkCreate("/usuarios/nuevo.txt", FileType.FILE, 4, UserMode.ADMINISTRATOR, null) == null);
        fs.createFile("/usuarios/nuevo.txt", 4, "admin", FilePermission.PUBLIC, "P7");
        check("el archivo nuevo reutiliza el hueco y guarda quién lo creó",
                fs.info("/usuarios/nuevo.txt").startBlock() == 119 && fs.info("/usuarios/nuevo.txt").creator().equals("P7"));
        check("y el disco vuelve a estar lleno", fs.freeBlocks() == 0);
    }

    private static void testDeleteDirectory() {
        BlockMap map = new BlockMap(200, 0);
        FileSystem fs = enunciadoFs(map);
        // /usuarios: notas 28 + img1 4 + data 57 + video 20 = 109 bloques
        check("eliminar un directorio libera todo lo que contiene (109 bloques)", fs.delete("/usuarios") == 109);
        check("el directorio y sus archivos desaparecen",
                !fs.exists("/usuarios") && !fs.exists("/usuarios/notas.txt") && !fs.exists("/usuarios/video.mp4"));
        check("quedan 109 bloques libres", fs.freeBlocks() == 109);
        check("lo demás sigue en su lugar", fs.exists("/sistema/boot.bin") && fs.exists("/mbr.bin"));
        check("no se puede eliminar la raíz", throwsIAE(() -> fs.delete("/")));
        check("eliminar algo que no existe lanza error", throwsIAE(() -> fs.delete("/usuarios")));
    }

    private static void testRename() {
        BlockMap map = new BlockMap(200, 0);
        FileSystem fs = enunciadoFs(map);
        fs.rename("/usuarios/notas.txt", "apuntes.txt");
        check("renombrar archivo: la ruta nueva existe y la vieja no",
                fs.exists("/usuarios/apuntes.txt") && !fs.exists("/usuarios/notas.txt"));
        check("el mapa de bloques muestra el nombre nuevo", map.snapshot()[34].owner().equals("/usuarios/apuntes.txt"));
        fs.rename("/usuarios", "users");
        check("renombrar directorio: sus archivos cambian de ruta",
                fs.exists("/users/video.mp4") && !fs.exists("/usuarios/video.mp4"));
        check("y el mapa de bloques de todos sus archivos también",
                map.snapshot()[180].owner().equals("/users/video.mp4") && map.snapshot()[34].owner().equals("/users/apuntes.txt"));
        check("renombrar a un nombre existente se rechaza", throwsIAE(() -> fs.rename("/users/img1.png", "data.csv")));
        check("renombrar con un nombre inválido se rechaza", throwsIAE(() -> fs.rename("/users/img1.png", "a/b")));
        check("no se puede renombrar la raíz", throwsIAE(() -> fs.rename("/", "x")));
    }

    private static void testPermissions() {
        BlockMap map = new BlockMap(50, 0);
        FileSystem fs = new FileSystem(map);
        fs.createDirectory("/home", "admin", FilePermission.PUBLIC, "inicial");
        fs.createDirectory("/home/user1", "user1", FilePermission.PUBLIC, "inicial");
        fs.createDirectory("/home/user2", "user2", FilePermission.PUBLIC, "inicial");
        fs.createFile("/home/user1/a.txt", 2, "user1", FilePermission.PRIVATE, "inicial");
        fs.createFile("/home/user2/b.txt", 2, "user2", FilePermission.PRIVATE, "inicial");
        fs.createFile("/home/user2/pub.txt", 1, "user2", FilePermission.PUBLIC, "inicial");
        UserMode user = UserMode.USER;
        UserMode admin = UserMode.ADMINISTRATOR;

        // leer
        check("usuario: lee su archivo privado", fs.checkRead("/home/user1/a.txt", user, "user1") == null);
        check("usuario: NO lee el archivo privado de otro", fs.checkRead("/home/user2/b.txt", user, "user1") != null);
        check("usuario: lee un archivo público de otro", fs.checkRead("/home/user2/pub.txt", user, "user1") == null);
        check("administrador: lee todo", fs.checkRead("/home/user2/b.txt", admin, null) == null);
        check("modo usuario sin indicar el usuario se rechaza", fs.checkRead("/home/user1/a.txt", user, null) != null);

        // crear
        check("usuario: crea un archivo en su directorio",
                fs.checkCreate("/home/user1/c.txt", FileType.FILE, 1, user, "user1") == null);
        check("usuario: NO crea en el directorio de otro",
                fs.checkCreate("/home/user2/c.txt", FileType.FILE, 1, user, "user1") != null);
        check("usuario: NO crea directorios",
                fs.checkCreate("/home/user1/sub", FileType.DIRECTORY, 0, user, "user1") != null);
        check("administrador: crea directorios", fs.checkCreate("/home/user1/sub", FileType.DIRECTORY, 0, admin, null) == null);
        check("no se crea algo que ya existe", fs.checkCreate("/home/user1/a.txt", FileType.FILE, 1, admin, null) != null);
        check("no se crea un archivo más grande que el espacio libre",
                fs.checkCreate("/home/big.bin", FileType.FILE, 100, admin, null) != null);

        // actualizar (renombrar)
        check("usuario: NO puede renombrar", fs.checkUpdate("/home/user1/a.txt", "z.txt", user, "user1") != null);
        check("administrador: renombra", fs.checkUpdate("/home/user1/a.txt", "z.txt", admin, null) == null);

        // eliminar
        check("usuario: elimina su archivo", fs.checkDelete("/home/user1/a.txt", user, "user1") == null);
        check("usuario: NO elimina el archivo de otro", fs.checkDelete("/home/user2/pub.txt", user, "user1") != null);
        check("usuario: NO elimina directorios", fs.checkDelete("/home/user1", user, "user1") != null);
        check("administrador: elimina directorios", fs.checkDelete("/home/user1", admin, null) == null);
        check("los rechazos explican el motivo", fs.checkDelete("/home/user2/pub.txt", user, "user1").contains("user2"));
    }

    private static void testInvalidAndErrors() {
        BlockMap map = new BlockMap(50, 0);
        FileSystem fs = new FileSystem(map);
        fs.createDirectory("/home", "admin", FilePermission.PUBLIC, "inicial");
        fs.createFile("/home/a.txt", 2, "admin", FilePermission.PUBLIC, "inicial");
        UserMode admin = UserMode.ADMINISTRATOR;
        String longName = "a".repeat(51);

        check("ruta sin / inicial", fs.checkCreate("home/x", FileType.FILE, 1, admin, null) != null);
        check("ruta con nombre vacío (//)", fs.checkCreate("/home//x", FileType.FILE, 1, admin, null) != null);
        check("no se crea la raíz", fs.checkCreate("/", FileType.DIRECTORY, 0, admin, null) != null);
        check("nombre de más de 50 caracteres", fs.checkCreate("/home/" + longName, FileType.FILE, 1, admin, null) != null);
        check("directorio padre inexistente", fs.checkCreate("/nada/x.txt", FileType.FILE, 1, admin, null) != null);
        check("no se crea dentro de un archivo", fs.checkCreate("/home/a.txt/x", FileType.FILE, 1, admin, null) != null);
        check("aplicar con un padre inexistente lanza error",
                throwsIAE(() -> fs.createFile("/nada/x.txt", 1, "admin", FilePermission.PUBLIC, "P1")));
        check("un archivo de 0 bloques lanza error",
                throwsIAE(() -> fs.createFile("/home/cero.txt", 0, "admin", FilePermission.PUBLIC, "P1")));
        check("un duplicado lanza error",
                throwsIAE(() -> fs.createFile("/home/a.txt", 1, "admin", FilePermission.PUBLIC, "P1")));
        check("un dueño vacío lanza error",
                throwsIAE(() -> fs.createFile("/home/b.txt", 1, " ", FilePermission.PUBLIC, "P1")));

        // cargar desde el JSON con bloques que se pisan: error y sin nodo a medias
        BlockMap map2 = new BlockMap(20, 0);
        FileSystem fs2 = new FileSystem(map2);
        fs2.loadFile("/a", 0, 5, "admin", FilePermission.PUBLIC, "inicial");
        check("cargar un archivo sobre bloques ocupados lanza error",
                throwsIAE(() -> fs2.loadFile("/b", 3, 5, "admin", FilePermission.PUBLIC, "inicial")));
        check("y no deja el archivo registrado", !fs2.exists("/b"));
        check("cargar fuera del disco lanza error",
                throwsIAE(() -> fs2.loadFile("/c", 18, 5, "admin", FilePermission.PUBLIC, "inicial")));
    }

    // ---------------------------------------------------------------- auxiliares

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
