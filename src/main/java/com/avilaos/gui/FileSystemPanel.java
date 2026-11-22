package com.avilaos.gui;

import com.avilaos.enums.FileType;
import com.avilaos.filesystem.FileSystem;
import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

// El JTree del sistema de archivos y, debajo, la información del elemento seleccionado (nombre, tamaño
// en bloques, dueño, permisos y primer bloque, como pide el enunciado). Solo lee snapshots de
// FileSystem. Solo se redibuja el árbol si algo cambió, para no perder la selección ni el scroll.
public class FileSystemPanel extends JPanel {

    private final FileSystem fileSystem;
    private final FileSystemTreeModel model;
    private final JTree tree;
    private final JTextArea details = new JTextArea(8, 28);
    private String lastSignature;

    public FileSystemPanel(FileSystem fileSystem) {
        this.fileSystem = fileSystem;
        FileSystem.EntryInfo[] snapshot = fileSystem.snapshot();
        this.model = new FileSystemTreeModel(snapshot);
        this.lastSignature = FileSystemTreeModel.signature(snapshot);

        tree = new JTree(model);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(e -> showDetails());
        expandAll();

        details.setEditable(false);
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Sistema de archivos"));
        JScrollPane detailsScroll = new JScrollPane(details);
        detailsScroll.setBorder(BorderFactory.createTitledBorder("Elemento seleccionado"));

        setLayout(new BorderLayout(4, 4));
        add(treeScroll, BorderLayout.CENTER);
        add(detailsScroll, BorderLayout.SOUTH);
        showDetails();
    }

    public void refresh() {
        FileSystem.EntryInfo[] snapshot = fileSystem.snapshot();
        String signature = FileSystemTreeModel.signature(snapshot);
        if (signature.equals(lastSignature)) {
            return;
        }
        String selected = selectedPath();
        model.reload(snapshot);
        expandAll();
        selectPath(selected);
        lastSignature = signature;
        showDetails();
    }

    // Al recargar el modelo el árbol se pliega: se vuelve a abrir todo (cada apertura agrega filas)
    private void expandAll() {
        int row = 0;
        while (row < tree.getRowCount()) {
            tree.expandRow(row);
            row++;
        }
    }

    private String selectedPath() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        return ((FileSystemTreeModel.TreeEntry) path.getLastPathComponent()).getInfo().path();
    }

    private void selectPath(String wanted) {
        if (wanted == null) {
            return;
        }
        for (int row = 0; row < tree.getRowCount(); row++) {
            TreePath path = tree.getPathForRow(row);
            String candidate = ((FileSystemTreeModel.TreeEntry) path.getLastPathComponent()).getInfo().path();
            if (candidate.equals(wanted)) {
                tree.setSelectionRow(row);
                return;
            }
        }
    }

    private void showDetails() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            details.setText("Selecciona un archivo o directorio del árbol.");
            return;
        }
        FileSystem.EntryInfo e = ((FileSystemTreeModel.TreeEntry) path.getLastPathComponent()).getInfo();
        boolean isFile = e.type() == FileType.FILE;
        details.setText("Nombre: " + (e.depth() == 0 ? "/ (raíz)" : e.name()) + "\n"
                + "Ruta: " + e.path() + "\n"
                + "Tipo: " + (isFile ? "Archivo" : "Directorio") + "\n"
                + "Tamaño en bloques: " + (isFile ? String.valueOf(e.blocks()) : "—") + "\n"
                + "Primer bloque: " + (isFile ? String.valueOf(e.startBlock()) : "—") + "\n"
                + "Dueño: " + e.owner() + "\n"
                + "Permisos: " + e.permission() + "\n"
                + "Creado por: " + e.creator());
        details.setCaretPosition(0);
    }
}
