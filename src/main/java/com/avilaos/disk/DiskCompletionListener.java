package com.avilaos.disk;

// El disco avisa por aquí cuando termina de atender una solicitud. Quien lo implemente
// (el kernel, a través del Cluster) despierta al proceso con ese id: pasa de BLOQUEADO a LISTO.
// Se llama fuera del lock del disco, así que puede volver a llamar a submitRequest.
public interface DiskCompletionListener {

    void onRequestCompleted(DiskRequest request, int cycle);
}
