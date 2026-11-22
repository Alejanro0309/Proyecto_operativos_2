package com.avilaos.gui;

import com.avilaos.enums.FileType;
import com.avilaos.filesystem.FileSystem;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

// Tabla de asignación de archivos: por cada archivo muestra su color en el mapa de bloques, su ruta,
// cantidad de bloques, primer bloque, dueño y el proceso que lo creó. Solo lee FileSystem.snapshot()
// y solo se rellena cuando algo cambió (así no pierde la selección ni el scroll en cada refresco).
// El color sale de FileColors.forOwner(ruta): BlockMap rotula los bloques con la ruta del archivo,
// por lo que el DiskPanel y esta tabla pintan el mismo archivo con el mismo color.
public class AllocationTablePanel extends JPanel {

    private static final String[] COLUMNS = {"Color", "Archivo", "Bloques", "Primer bloque", "Dueño", "Creado por"};

    private final FileSystem fileSystem;
    private final TitledBorder border = new TitledBorder("Tabla de asignación de archivos");
    private final JTable table;
    private final DefaultTableModel model = new DefaultTableModel(new Object[0][0], COLUMNS) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Color.class : Object.class;
        }
    };
    private String lastSignature;

    public AllocationTablePanel(FileSystem fileSystem) {
        if (fileSystem == null) {
            throw new IllegalArgumentException("La tabla necesita un sistema de archivos");
        }
        this.fileSystem = fileSystem;
        table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setDefaultRenderer(Color.class, new ColorCellRenderer());
        int[] widths = {40, 220, 70, 90, 80, 90};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(border);
        setLayout(new BorderLayout());
        add(scroll, BorderLayout.CENTER);
        refresh();
    }

    public void refresh() {
        FileSystem.EntryInfo[] entries = fileSystem.snapshot();
        String signature = FileSystemTreeModel.signature(entries);
        if (signature.equals(lastSignature)) {
            return;
        }
        lastSignature = signature;
        int selected = table.getSelectedRow();
        Object selectedPath = selected >= 0 ? model.getValueAt(selected, 1) : null;

        model.setRowCount(0);
        for (int i = 0; i < entries.length; i++) {
            FileSystem.EntryInfo e = entries[i];
            if (e.type() != FileType.FILE) {
                continue;                       // los directorios no ocupan bloques
            }
            model.addRow(new Object[]{FileColors.forOwner(e.path()), e.path(), e.blocks(),
                e.startBlock(), e.owner(), e.creator()});
        }
        border.setTitle("Tabla de asignación de archivos (" + model.getRowCount() + ")");
        if (selectedPath != null) {
            for (int r = 0; r < model.getRowCount(); r++) {
                if (selectedPath.equals(model.getValueAt(r, 1))) {
                    table.setRowSelectionInterval(r, r);
                    break;
                }
            }
        }
        repaint();
    }

    // Pinta la celda entera con el color del archivo (sin texto)
    private static class ColorCellRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(t, "", isSelected, hasFocus, row, column);
            if (value instanceof Color color) {
                setBackground(color);
            }
            setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, Color.WHITE));
            return this;
        }
    }
}
