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
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

// Mapa de bloques del disco: un cuadro por bloque (gris = libre, color = archivo, azul claro = swap),
// con el cabezal marcado en rojo y su dirección. Solo lee snapshots de Disk y BlockMap.
public class DiskPanel extends JPanel {

    private static final int COLS = 40;
    private static final int CELL = 22;

    private final Disk disk;
    private final BlockMap map;
    private final JLabel headLabel = new JLabel("—");
    private final JLabel currentLabel = new JLabel("—");
    private final JLabel usageLabel = new JLabel("—");
    private final MapCanvas canvas = new MapCanvas();
    private BlockInfo[] blocks = new BlockInfo[0];
    private int head;

    public DiskPanel(Disk disk, BlockMap map) {
        if (disk == null || map == null) {
            throw new IllegalArgumentException("El panel necesita el disco y su mapa de bloques");
        }
        this.disk = disk;
        this.map = map;

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        top.add(headLabel);
        top.add(currentLabel);
        top.add(usageLabel);
        top.add(new JLabel("Gris = libre · Azul claro = swap · Borde rojo = cabezal · Pase el mouse sobre un bloque"));

        JScrollPane scroll = new JScrollPane(canvas);
        scroll.setBorder(BorderFactory.createTitledBorder("Mapa de bloques del disco"));
        setLayout(new BorderLayout(4, 4));
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        refresh();
    }

    public void refresh() {
        DiskStatus st = disk.snapshot();
        blocks = map.snapshot();
        head = st.headPosition();
        DiskRequest cur = st.current();
        headLabel.setText("Política: " + st.policy() + "   |   Cabezal: bloque " + head + "   |   Dirección: "
                + (st.direction() == HeadDirection.UP ? "↑ hacia bloques mayores" : "↓ hacia bloques menores"));
        currentLabel.setText("Atendiendo: " + (cur == null ? "nadie (disco libre)" : cur));
        int freeFiles = map.freeFileBlocks();
        int freeSwap = map.freeSwapBlocks();
        usageLabel.setText("Archivos: " + (map.getFileAreaBlocks() - freeFiles) + " ocupados / " + freeFiles
                + " libres   |   Swap: " + (map.getSwapAreaBlocks() - freeSwap) + " ocupados / " + freeSwap + " libres");
        int rows = (blocks.length + COLS - 1) / COLS;
        Dimension size = new Dimension(COLS * CELL + 1, rows * CELL + 1);
        if (!size.equals(canvas.getPreferredSize())) {
            canvas.setPreferredSize(size);
            canvas.revalidate();
        }
        canvas.repaint();
    }

    private class MapCanvas extends JPanel {

        MapCanvas() {
            setBackground(Color.WHITE);
            setToolTipText("");
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
            for (int i = 0; i < blocks.length; i++) {
                int x = (i % COLS) * CELL;
                int y = (i / COLS) * CELL;
                g2.setColor(FileColors.forBlock(blocks[i].type(), blocks[i].owner()));
                g2.fillRect(x, y, CELL, CELL);
                g2.setColor(Color.LIGHT_GRAY);
                g2.drawRect(x, y, CELL, CELL);
                g2.setColor(Color.BLACK);
                g2.drawString(String.valueOf(i), x + 2, y + CELL - 8);
            }
            if (head >= 0 && head < blocks.length) {
                g2.setColor(Color.RED);
                g2.setStroke(new BasicStroke(3f));
                g2.drawRect((head % COLS) * CELL, (head / COLS) * CELL, CELL, CELL);
            }
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int col = e.getX() / CELL;
            int row = e.getY() / CELL;
            int i = row * COLS + col;
            if (col >= COLS || i < 0 || i >= blocks.length) {
                return null;
            }
            BlockInfo b = blocks[i];
            if (b.type() == BlockOwnerType.FREE) {
                return "Bloque " + i + ": libre";
            }
            return "Bloque " + i + ": " + b.owner() + (b.type() == BlockOwnerType.SWAP ? " (swap)" : "")
                    + (b.next() >= 0 ? " → siguiente: " + b.next() : " (último de su cadena)");
        }
    }
}
