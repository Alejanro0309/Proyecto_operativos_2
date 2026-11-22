package com.avilaos.tests;

import com.avilaos.disk.BlockMap;
import com.avilaos.enums.FilePermission;
import com.avilaos.filesystem.FileSystem;
import com.avilaos.gui.FileSystemTreeModel;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;

// Prueba del modelo del JTree (estructura propia): se arma desde FileSystem.snapshot(), respeta la jerarquía,
// distingue hojas (archivos) de directorios, avisa a la vista al recargarse y detecta cambios con la firma.
// No abre ventanas: solo usa el modelo, así que corre sin interfaz.
public class FileSystemTreeModelTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testStructure();
        testReloadAndListeners();
        testSignature();
        System.out.println("--------------------------------");
        System.out.println("Pruebas OK: " + passed + " | Fallidas: " + failed);
    }

    private static FileSystem sampleFs() {
        FileSystem fs = new FileSystem(new BlockMap(50, 0));
        fs.createDirectory("/home", "admin", FilePermission.PUBLIC, "inicial");
        fs.createDirectory("/home/user1", "user1", FilePermission.PUBLIC, "inicial");
        fs.createFile("/home/user1/a.txt", 2, "user1", FilePermission.PRIVATE, "inicial");
        fs.createFile("/home/b.txt", 1, "admin", FilePermission.PUBLIC, "inicial");
        return fs;
    }

    private static void testStructure() {
        FileSystemTreeModel model = new FileSystemTreeModel(sampleFs().snapshot());
        Object root = model.getRoot();
        check("la raíz se muestra como /", root.toString().equals("/"));
        check("la raíz tiene 1 hijo (/home)", model.getChildCount(root) == 1);
        Object home = model.getChild(root, 0);
        check("el hijo de la raíz se llama home", home.toString().equals("home"));
        check("home tiene 2 hijos (user1 y b.txt)", model.getChildCount(home) == 2);
        Object user1 = model.getChild(home, 0);
        Object b = model.getChild(home, 1);
        check("el primero de home es el directorio user1 y el segundo el archivo b.txt",
                user1.toString().equals("user1") && b.toString().equals("b.txt"));
        check("un directorio no es hoja y un archivo sí", !model.isLeaf(user1) && model.isLeaf(b));
        Object a = model.getChild(user1, 0);
        check("a.txt está dentro de user1 y es hoja", a.toString().equals("a.txt") && model.isLeaf(a));
        check("getIndexOfChild devuelve la posición", model.getIndexOfChild(home, b) == 1 && model.getIndexOfChild(home, a) == -1);
        FileSystem.EntryInfo info = ((FileSystemTreeModel.TreeEntry) a).getInfo();
        check("cada nodo guarda los datos del archivo", info.path().equals("/home/user1/a.txt") && info.blocks() == 2
                && info.owner().equals("user1") && info.permission() == FilePermission.PRIVATE);
        check("el modelo rechaza una copia vacía", throwsIAE(() -> new FileSystemTreeModel(new FileSystem.EntryInfo[0])));
    }

    private static void testReloadAndListeners() {
        FileSystem fs = sampleFs();
        FileSystemTreeModel model = new FileSystemTreeModel(fs.snapshot());
        final int[] changes = {0};
        TreeModelListener listener = new TreeModelListener() {
            @Override
            public void treeNodesChanged(TreeModelEvent e) {
            }

            @Override
            public void treeNodesInserted(TreeModelEvent e) {
            }

            @Override
            public void treeNodesRemoved(TreeModelEvent e) {
            }

            @Override
            public void treeStructureChanged(TreeModelEvent e) {
                changes[0]++;
            }
        };
        model.addTreeModelListener(listener);
        fs.createFile("/home/c.txt", 1, "admin", FilePermission.PUBLIC, "P1");
        model.reload(fs.snapshot());
        Object home = model.getChild(model.getRoot(), 0);
        check("al recargar, home pasa a tener 3 hijos", model.getChildCount(home) == 3);
        check("al recargar se avisa una vez a la vista", changes[0] == 1);
        model.removeTreeModelListener(listener);
        model.reload(fs.snapshot());
        check("un listener quitado ya no recibe avisos", changes[0] == 1);
    }

    private static void testSignature() {
        FileSystem fs = sampleFs();
        String before = FileSystemTreeModel.signature(fs.snapshot());
        check("la firma no cambia si no cambia nada", before.equals(FileSystemTreeModel.signature(fs.snapshot())));
        fs.rename("/home/b.txt", "z.txt");
        check("la firma cambia al renombrar", !before.equals(FileSystemTreeModel.signature(fs.snapshot())));
        String renamed = FileSystemTreeModel.signature(fs.snapshot());
        fs.delete("/home/z.txt");
        check("la firma cambia al eliminar", !renamed.equals(FileSystemTreeModel.signature(fs.snapshot())));
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
