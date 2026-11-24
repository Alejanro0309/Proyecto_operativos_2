package com.avilaos.filesystem;

import com.avilaos.enums.FilePermission;
import com.avilaos.enums.FileType;
import com.avilaos.enums.IoOperation;
import com.avilaos.enums.UserMode;
// Lo que el usuario pidió hacer sobre un archivo: la interfaz solo arma esto y se lo entrega a un
// FileOperationRequester; quien lo implemente crea el proceso de E/S (la interfaz nunca toca el disco).
// Los campos que no aplican a la operación van en null (o 0): CREATE usa createType, createBlocks y
// createPermission; UPDATE usa newName; READ y DELETE solo la ruta. 'owner' es el usuario que lo pide.
public record FileOperation(
        IoOperation operation,
        String path,
        FileType createType,
        int createBlocks,
        FilePermission createPermission,
        String newName,
        String owner,
        UserMode mode) {
}
