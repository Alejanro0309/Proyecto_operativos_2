package com.avilaos.gui;

import com.avilaos.disk.BlockInfo;
import com.avilaos.disk.BlockMap;
import com.avilaos.disk.DiskRequest;
import com.avilaos.disk.DiskStatus;
import com.avilaos.enums.BlockOwnerType;
import com.avilaos.enums.HeadDirection;
import com.avilaos.hardware.Disk;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.ToolTipManager;

// Mapa del disco: un cuadro por bloque (numerados por posición), con el color del archivo que lo ocupa,
// la posición del cabezal (marca roja) y el bloque al que va la solicitud en curso (marco naranja).
// Abajo, la zona de intercambio (swap). Pasando el mouse por un bloque se ve su número, su dueño y el
// siguiente bloque de la cadena. Solo lee snapshots de BlockMap y de Disk.
public class DiskPanel extends JPanel {

    private static final int COLUMNS = 50;
    private static final int CELL_HEIGHT = 16;
    private static final int MARGIN = 8;
    private static final int HEAD_ROOM = 14;     // espacio sobre el mapa para la marca del cabezal

    private final BlockMap blockMap;
    private final Disk disk;
    private final JLabel statusLabel = new JLabel(" ");
    private final BlockCanvas canvas = new BlockCanvas();
    private BlockInfo[] blocks = new BlockInfo[0];
    private DiskStatus status;

    public DiskPanel(BlockMap blockMap, Disk disk) {
        this.blockMap = blockMap;
        this.disk = disk;
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createTitledBorder("Disco (SD): bloques y cabezal"));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        JLabel legend = new JLabel("Gris: libre · Colores: archivos (ver la tabla de asignación) · Azul: swap · "
                + "Rojo: cabezal · Naranja: destino de la solicitud en curso");
        legend.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        add(statusLabel, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(legend, BorderLayout.SOUTH);
        refresh();
    }

    public void refresh() {
        blocks = blockMap.snapshot();
        status = disk.snapshot();
        String direction = status.direction() == HeadDirection.UP ? "↑ hacia bloques mayores" : "↓ hacia bloques menores";
        DiskRequest current = status.current();
        statusLabel.setText("Cabezal: bloque " + status.headPosition() + "   |   Dirección: " + direction
                + "   |   Política: " + status.policy()
                + "   |   " + (current == null ? "Disco libre" : "Atendiendo: " + current)
                + "   |   En cola: " + status.queue().length);
        canvas.setPreferredSize(new Dimension(COLUMNS * 14 + 2 * MARGIN, canvasHeight()));
        canvas.revalidate();
        canvas.repaint();
    }

    private int fileArea() {
        return status == null ? blocks.length : status.totalBlocks();
    }

    private static int rowsOf(int count) {
        return (count + COLUMNS - 1) / COLUMNS;
    }

    private int swapTop() {
        return HEAD_ROOM + rowsOf(fileArea()) * CELL_HEIGHT + 26;
    }

    private int canvasHeight() {
        int swapBlocks = Math.max(0, blocks.length - fileArea());
        return swapTop() + (swapBlocks == 0 ? 0 : rowsOf(swapBlocks) * CELL_HEIGHT) + 10;
    }

    // ---------------------------------------------------------------- dibujo

    private class BlockCanvas extends JPanel {

        BlockCanvas() {
            ToolTipManager.sharedInstance().registerComponent(this);
        }

        private int cellWidth() {
            return Math.max(4, (getWidth() - 2 * MARGIN) / COLUMNS);
        }

        // Rectángulo del bloque 'index': los de swap (después de la zona de archivos) van en su propia fila
        private Rectangle cellRect(int index) {
            int area = fileArea();
            int w = cellWidth();
            if (index < area) {
                return new Rectangle(MARGIN + (index % COLUMNS) * w, HEAD_ROOM + (index / COLUMNS) * CELL_HEIGHT,
                        w, CELL_HEIGHT);
            }
            int s = index - area;
            return new Rectangle(MARGIN + (s % COLUMNS) * w, swapTop() + (s / COLUMNS) * CELL_HEIGHT, w, CELL_HEIGHT);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            for (int i = 0; i < blocks.length; i++) {
                Rectangle r = cellRect(i);
                g2.setColor(FileColors.forBlock(blocks[i].type(), blocks[i].owner()));
                g2.fillRect(r.x, r.y, r.width - 1, r.height - 1);
                g2.setColor(new Color(200, 200, 200));
                g2.drawRect(r.x, r.y, r.width - 1, r.height - 1);
            }
            if (blocks.length > fileArea()) {
                g2.setColor(Color.DARK_GRAY);
                g2.drawString("Zona de intercambio (swap)", MARGIN, swapTop() - 8);
            }
            if (status == null || blocks.length == 0) {
                return;
            }
            // destino de la solicitud en curso
            if (status.current() != null && status.current().getBlock() < blocks.length) {
                Rectangle t = cellRect(status.current().getBlock());
                g2.setColor(new Color(255, 140, 0));
                g2.setStroke(new BasicStroke(2));
                g2.drawRect(t.x, t.y, t.width - 2, t.height - 2);
            }
            // cabezal: marco rojo y flecha sobre el bloque
            if (status.headPosition() < blocks.length) {
                Rectangle h = cellRect(status.headPosition());
                g2.setColor(Color.RED);
                g2.setStroke(new BasicStroke(2));
                g2.drawRect(h.x, h.y, h.width - 2, h.height - 2);
                int cx = h.x + h.width / 2;
                Polygon arrow = new Polygon(new int[]{cx - 5, cx + 5, cx}, new int[]{h.y - HEAD_ROOM + 2, h.y - HEAD_ROOM + 2, h.y - 1}, 3);
                g2.fillPolygon(arrow);
            }
        }

        // Tooltip con los datos del bloque bajo el mouse
        @Override
        public String getToolTipText(MouseEvent e) {
            for (int i = 0; i < blocks.length; i++) {
                if (cellRect(i).contains(e.getPoint())) {
                    BlockInfo b = blocks[i];
                    if (b.type() == BlockOwnerType.FREE) {
                        return "Bloque " + i + ": libre";
                    }
                    return "Bloque " + i + ": " + b.owner()
                            + (b.next() == -1 ? " (último bloque)" : " → siguiente: " + b.next());
                }
            }
            return null;
        }
    }
}
