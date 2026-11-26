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

// Mapa de bloques del disco: un cuadro por bloque (gris = libre, color = archivo), con el cabezal en
// rojo y su dirección. El área de intercambio (swap) se dibuja aparte, debajo, con su propio título.
// Cada archivo tiene su color (según su primer bloque). Solo lee snapshots de Disk y BlockMap.
public class DiskPanel extends JPanel {

    private static final int COLS = 40;
    private static final int CELL = 22;
    private static final int GAP = 26;       // franja con el título del swap

    private final Disk disk;
    private final BlockMap map;
    private final JLabel headLabel = new JLabel("—");
    private final JLabel currentLabel = new JLabel("—");
    private final JLabel usageLabel = new JLabel("—");
    private final MapCanvas canvas = new MapCanvas();
    private BlockInfo[] blocks = new BlockInfo[0];
    private Color[] colors = new Color[0];
    private int fileArea;
    private int head;

    public DiskPanel(Disk disk, BlockMap map) {
        if (disk == null || map == null) {
            throw new IllegalArgumentException("El panel necesita el disco y su mapa de bloques");
        }
        this.disk = disk;
        this.map = map;
        this.fileArea = map.getFileAreaBlocks();

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
        colors = computeColors(blocks);
        head = st.headPosition();
        DiskRequest cur = st.current();
        headLabel.setText("Política: " + st.policy() + "   |   Cabezal: bloque " + head + "   |   Dirección: "
                + (st.direction() == HeadDirection.UP ? "↑ hacia bloques mayores" : "↓ hacia bloques menores"));
        currentLabel.setText("Atendiendo: " + (cur == null ? "nadie (disco libre)" : cur));
        int freeFiles = map.freeFileBlocks();
        int freeSwap = map.freeSwapBlocks();
        usageLabel.setText("Archivos: " + (map.getFileAreaBlocks() - freeFiles) + " ocupados / " + freeFiles
                + " libres   |   Swap: " + (map.getSwapAreaBlocks() - freeSwap) + " ocupados / " + freeSwap + " libres");
        Dimension size = new Dimension(COLS * CELL + 1, totalHeight());
        if (!size.equals(canvas.getPreferredSize())) {
            canvas.setPreferredSize(size);
            canvas.revalidate();
        }
        canvas.repaint();
    }

    // El color de cada bloque. Cada archivo es una cadena: su primer bloque es el que ningún otro apunta.
    private Color[] computeColors(BlockInfo[] b) {
        int n = b.length;
        boolean[] pointed = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (b[i].type() != BlockOwnerType.FREE && b[i].next() >= 0 && b[i].next() < n) {
                pointed[b[i].next()] = true;
            }
        }
        int[] starts = new int[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (b[i].type() == BlockOwnerType.FILE && !pointed[i]) {
                starts[count++] = i;
            }
        }
        int[] startOf = new int[n];
        for (int i = 0; i < n; i++) {
            startOf[i] = -1;
        }
        for (int s = 0; s < count; s++) {
            int cur = starts[s];
            int steps = 0;
            while (cur >= 0 && cur < n && steps++ <= n) {
                startOf[cur] = starts[s];
                cur = b[cur].next();
            }
        }
        Color[] result = new Color[n];
        for (int i = 0; i < n; i++) {
            if (b[i].type() == BlockOwnerType.FREE) {
                result[i] = FileColors.FREE;
            } else if (b[i].type() == BlockOwnerType.SWAP) {
                result[i] = FileColors.SWAP;
            } else if (startOf[i] >= 0) {
                result[i] = FileColors.forFile(startOf[i], starts, count);
            } else {
                result[i] = FileColors.forOwner(b[i].owner());
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- posiciones en el dibujo

    private int fileRows() {
        return (fileArea + COLS - 1) / COLS;
    }

    private int xOf(int i) {
        int j = i < fileArea ? i : i - fileArea;
        return (j % COLS) * CELL;
    }

    private int yOf(int i) {
        if (i < fileArea) {
            return (i / COLS) * CELL;
        }
        return fileRows() * CELL + GAP + ((i - fileArea) / COLS) * CELL;
    }

    private int totalHeight() {
        int swap = Math.max(0, blocks.length - fileArea);
        if (swap == 0) {
            return fileRows() * CELL + 1;
        }
        return fileRows() * CELL + GAP + ((swap + COLS - 1) / COLS) * CELL + 1;
    }

    // El bloque que está en el punto (x, y) del dibujo, o -1
    private int blockAt(int x, int y) {
        int col = x / CELL;
        if (col >= COLS || x < 0 || y < 0) {
            return -1;
        }
        int fileHeight = fileRows() * CELL;
        int i;
        if (y < fileHeight) {
            i = (y / CELL) * COLS + col;
            return i < fileArea ? i : -1;
        }
        if (y < fileHeight + GAP) {
            return -1;
        }
        i = fileArea + ((y - fileHeight - GAP) / CELL) * COLS + col;
        return i < blocks.length ? i : -1;
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
                int x = xOf(i);
                int y = yOf(i);
                g2.setColor(colors[i]);
                g2.fillRect(x, y, CELL, CELL);
                g2.setColor(Color.LIGHT_GRAY);
                g2.drawRect(x, y, CELL, CELL);
                g2.setColor(Color.BLACK);
                g2.drawString(String.valueOf(i), x + 2, y + CELL - 8);
            }
            if (blocks.length > fileArea) {
                int y = fileRows() * CELL;
                g2.setColor(new Color(70, 100, 160));
                g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                g2.drawString("Área de intercambio (swap): bloques " + fileArea + " a " + (blocks.length - 1),
                        2, y + GAP - 8);
                g2.drawLine(0, y + GAP - 3, COLS * CELL, y + GAP - 3);
            }
            if (head >= 0 && head < blocks.length) {
                g2.setColor(Color.RED);
                g2.setStroke(new BasicStroke(3f));
                g2.drawRect(xOf(head), yOf(head), CELL, CELL);
            }
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int i = blockAt(e.getX(), e.getY());
            if (i < 0) {
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