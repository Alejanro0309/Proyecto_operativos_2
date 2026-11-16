package com.avilaos.filesystem;

import com.avilaos.datastructures.CustomLinkedList;
import com.avilaos.enums.FilePermission;
import com.avilaos.enums.FileType;

// Nodo del árbol del sistema de archivos: un archivo o un directorio.
// Un directorio tiene hijos (lista enlazada propia) y no ocupa bloques. Un archivo guarda su primer
// bloque y su tamaño en bloques; la cadena completa de bloques vive en BlockMap.
//
// No tiene sincronización propia: FileSystem lo protege con su Semaphore. Los métodos que modifican
// el árbol son de paquete, para que solo FileSystem pueda usarlos.
public class FsNode {

    private String name;                  // la raíz tiene nombre vacío
    private final FileType type;
    private final String owner;
    private final FilePermission permission;
    private final int startBlock;         // -1 en los directorios
    private final int blocks;             // 0 en los directorios
    private final String creator;         // proceso que lo creó (para la tabla de asignación)
    private FsNode parent;
    private final CustomLinkedList<FsNode> children;   // null en los archivos

    FsNode(String name, FileType type, String owner, FilePermission permission,
            int startBlock, int blocks, String creator) {
        this.name = name;
        this.type = type;
        this.owner = owner;
        this.permission = permission;
        this.startBlock = startBlock;
        this.blocks = blocks;
        this.creator = creator;
        this.children = type == FileType.DIRECTORY ? new CustomLinkedList<FsNode>() : null;
    }

    public String getName() { return name; }
    public FileType getType() { return type; }
    public String getOwner() { return owner; }
    public FilePermission getPermission() { return permission; }
    public int getStartBlock() { return startBlock; }
    public int getBlocks() { return blocks; }
    public String getCreator() { return creator; }
    public FsNode getParent() { return parent; }

    public boolean isDirectory() {
        return type == FileType.DIRECTORY;
    }

    // La ruta se arma subiendo por los padres, así al renombrar un directorio las rutas de todo
    // lo que contiene cambian solas.
    public String getPath() {
        if (parent == null) {
            return "/";
        }
        String parentPath = parent.getPath();
        return (parentPath.equals("/") ? "" : parentPath) + "/" + name;
    }

    // ---------------------------------------------------------------- solo FileSystem

    void setName(String newName) {
        this.name = newName;
    }

    int childCount() {
        return children.getSize();
    }

    FsNode childAt(int index) {
        return children.get(index);
    }

    FsNode findChild(String childName) {
        for (int i = 0; i < children.getSize(); i++) {
            if (children.get(i).name.equals(childName)) {
                return children.get(i);
            }
        }
        return null;
    }

    void addChild(FsNode child) {
        child.parent = this;
        children.add(child);
    }

    void removeChild(FsNode child) {
        for (int i = 0; i < children.getSize(); i++) {
            if (children.get(i) == child) {
                children.removeAt(i);
                child.parent = null;
                return;
            }
        }
    }

    @Override
    public String toString() {
        return getPath() + (isDirectory() ? "/" : " (" + blocks + " bloques desde el " + startBlock + ")");
    }
}
