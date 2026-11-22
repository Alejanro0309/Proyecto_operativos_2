package com.avilaos.gui;

import com.avilaos.config.FileSystemLoader;
import com.avilaos.filesystem.FileOperationRequester;
import com.avilaos.filesystem.FileSystem;
import com.avilaos.hardware.Disk;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

// Pestaña "Disco y archivos": reúne el mapa de bloques con el cabezal, el JTree, la tabla de asignación y
// la cola de E/S del disco. Arriba tiene el botón para guardar el estado del sistema de archivos en un JSON
// (para cargarlo en una ejecución futura). No decide nada: solo observa y manda órdenes.
public class DiskSystemPanel extends JPanel {

    private final FileSystem fileSystem;
    private final Disk disk;
    private final DiskPanel diskPanel;
    private final FileSystemPanel fileSystemPanel;
    private final AllocationTablePanel allocationPanel;
    private final DiskQueuePanel queuePanel;
    private final JLabel freeLabel = new JLabel(" ");

    public DiskSystemPanel(FileSystem fileSystem, Disk disk, UserSession session, FileOperationRequester requester) {
        this.fileSystem = fileSystem;
        this.disk = disk;
        diskPanel = new DiskPanel(disk, fileSystem.getBlockMap()); // <-- Orden correcto: (Disk, BlockMap)
        fileSystemPanel = new FileSystemPanel(fileSystem);
        allocationPanel = new AllocationTablePanel(fileSystem);
        queuePanel = new DiskQueuePanel(disk);                     // <-- Solo requiere Disk

        JButton saveButton = new JButton("Guardar sistema de archivos...");
        saveButton.addActionListener(e -> saveState());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(saveButton);
        bar.add(freeLabel);

        JTabbedPane right = new JTabbedPane();
        right.addTab("Tabla de asignación", allocationPanel);
        right.addTab("Cola de E/S del disco", queuePanel);
        JSplitPane horizontal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, fileSystemPanel, right);
        horizontal.setResizeWeight(0.35);

        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.NORTH);
        north.add(diskPanel, BorderLayout.CENTER);

        setLayout(new BorderLayout());
        add(north, BorderLayout.NORTH);
        add(horizontal, BorderLayout.CENTER);
        refresh();
    }

    public void refresh() {
        diskPanel.refresh();
        fileSystemPanel.refresh();
        allocationPanel.refresh();
        queuePanel.refresh();
        int area = fileSystem.getBlockMap().getFileAreaBlocks();
        freeLabel.setText("   Bloques libres: " + fileSystem.freeBlocks() + " de " + area
                + "   |   Swap libre: " + fileSystem.getBlockMap().freeSwapBlocks()
                + " de " + fileSystem.getBlockMap().getSwapAreaBlocks());
    }

    // Guarda el estado actual (directorios, archivos, bloques, dueños y permisos) con la posición del cabezal
    private void saveState() {
        JFileChooser chooser = new JFileChooser(new File("config"));
        chooser.setFileFilter(new FileNameExtensionFilter("Sistema de archivos (*.json)", "json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().endsWith(".json")) {
            file = new File(file.getPath() + ".json");
        }
        try {
            FileSystemLoader.save(fileSystem, disk.getTotalBlocks(), disk.snapshot().headPosition(), file);
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(this),
                    "Sistema de archivos guardado en " + file.getName());
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "No se pudo guardar", JOptionPane.ERROR_MESSAGE);
        }
    }
}
