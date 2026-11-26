package com.avilaos.gui;

import com.avilaos.enums.FileType;
import com.avilaos.filesystem.FileSystem;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeModel;

// Modelo del JTree con estructura PROPIA (no DefaultMutableTreeNode, que guarda sus hijos en un Vector).
// Se arma a partir de FileSystem.snapshot(): las entradas vienen en preorden con su profundidad, así que
// el padre de cada una es la última entrada vista a la profundidad anterior. Es una copia: la interfaz
// nunca toca los nodos reales del sistema de archivos.
public class FileSystemTreeModel implements TreeModel {

    // Una entrada del árbol: sus datos y sus hijos (arreglo que crece de a uno).
    public static final class TreeEntry {

        private final FileSystem.EntryInfo info;
        private TreeEntry[] children = new TreeEntry[0];

        TreeEntry(FileSystem.EntryInfo info) {
            this.info = info;
        }

        void addChild(TreeEntry child) {
            TreeEntry[] bigger = new TreeEntry[children.length + 1];
            for (int i = 0; i < children.length; i++) {
                bigger[i] = children[i];
            }
            bigger[children.length] = child;
            children = bigger;
        }

        public FileSystem.EntryInfo getInfo() {
            return info;
        }

        // Lo que muestra el JTree
        @Override
        public String toString() {
            return info.depth() == 0 ? "/" : info.name();
        }
    }

    private TreeEntry root;
    private TreeModelListener[] listeners = new TreeModelListener[0];

    public FileSystemTreeModel(FileSystem.EntryInfo[] snapshot) {
        this.root = build(snapshot);
    }

    // Reemplaza el contenido y avisa a la vista para que se redibuje
    public void reload(FileSystem.EntryInfo[] snapshot) {
        this.root = build(snapshot);
        TreeModelEvent event = new TreeModelEvent(this, new Object[]{root});
        TreeModelListener[] copy = listeners;
        for (int i = 0; i < copy.length; i++) {
            copy[i].treeStructureChanged(event);
        }
    }

    // Texto que cambia cuando cambia cualquier dato visible: sirve para redibujar solo si hubo cambios
    public static String signature(FileSystem.EntryInfo[] entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.length; i++) {
            FileSystem.EntryInfo e = entries[i];
            sb.append(e.path()).append('|').append(e.type()).append('|').append(e.owner()).append('|')
                    .append(e.permission()).append('|').append(e.startBlock()).append('|')
                    .append(e.blocks()).append('|').append(e.creator()).append('\n');
        }
        return sb.toString();
    }

    private static TreeEntry build(FileSystem.EntryInfo[] entries) {
        if (entries == null || entries.length == 0) {
            throw new IllegalArgumentException("El sistema de archivos siempre tiene la raíz");
        }
        TreeEntry[] lastAtDepth = new TreeEntry[entries.length + 1];
        TreeEntry rootEntry = null;
        for (int i = 0; i < entries.length; i++) {
            TreeEntry node = new TreeEntry(entries[i]);
            int depth = entries[i].depth();
            if (depth == 0) {
                rootEntry = node;
            } else if (lastAtDepth[depth - 1] != null) {
                lastAtDepth[depth - 1].addChild(node);
            }
            lastAtDepth[depth] = node;
        }
        return rootEntry;
    }

    // ---------------------------------------------------------------- TreeModel

    @Override
    public Object getRoot() {
        return root;
    }

    @Override
    public Object getChild(Object parent, int index) {
        return ((TreeEntry) parent).children[index];
    }

    @Override
    public int getChildCount(Object parent) {
        return ((TreeEntry) parent).children.length;
    }

    @Override
    public boolean isLeaf(Object node) {
        return ((TreeEntry) node).info.type() == FileType.FILE;
    }

    @Override
    public void valueForPathChanged(TreePath path, Object newValue) {
        // solo lectura: renombrar se hace con un proceso de E/S, no desde el árbol
    }

    @Override
    public int getIndexOfChild(Object parent, Object child) {
        TreeEntry[] children = ((TreeEntry) parent).children;
        for (int i = 0; i < children.length; i++) {
            if (children[i] == child) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void addTreeModelListener(TreeModelListener listener) {
        TreeModelListener[] bigger = new TreeModelListener[listeners.length + 1];
        for (int i = 0; i < listeners.length; i++) {
            bigger[i] = listeners[i];
        }
        bigger[listeners.length] = listener;
        listeners = bigger;
    }

    @Override
    public void removeTreeModelListener(TreeModelListener listener) {
        int at = -1;
        for (int i = 0; i < listeners.length; i++) {
            if (listeners[i] == listener) {
                at = i;
            }
        }
        if (at == -1) {
            return;
        }
        TreeModelListener[] smaller = new TreeModelListener[listeners.length - 1];
        for (int i = 0, j = 0; i < listeners.length; i++) {
            if (i != at) {
                smaller[j++] = listeners[i];
            }
        }
        listeners = smaller;
    }
}
