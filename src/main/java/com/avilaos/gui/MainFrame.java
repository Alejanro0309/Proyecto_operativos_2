package com.avilaos.gui;

import com.avilaos.config.SimulationConfig;
import com.avilaos.hardware.CompositeClockListener;
import com.avilaos.metrics.DiskMetricsCollector;
import com.avilaos.metrics.MetricsCollector;
import com.avilaos.system.Cluster;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.Timer;

// Ventana principal. Arriba, pestañas; abajo, el panel global (reloj, log, modo de usuario).
// Si el clúster tiene disco (Proyecto 2) se agregan las pestañas Archivos, Disco, Métricas del disco
// y Gráfica del cabezal. Un Timer de Swing relee todo cada 100 ms; solo se refresca la pestaña visible
// (el árbol y la tabla de asignación siempre: solo se redibujan si algo cambió).
public class MainFrame extends JFrame {

    private static final int REFRESH_MS = 100;

    private final Cluster cluster;
    private final MetricsCollector collector;
    private final JTabbedPane tabs = new JTabbedPane();
    private final SummaryPanel summaryPanel;
    private final ComputerPanel[] computerPanels;
    private final MetricsPanel metricsPanel;
    private final UtilizationChartPanel chartPanel;
    private final GlobalPanel globalPanel;
    private final Timer timer;
    private final Runnable onBack;

    // Proyecto 2 (null en el Proyecto 1)
    private FileSystemPanel fsPanel;
    private AllocationTablePanel allocPanel;
    private JSplitPane filesTab;
    private DiskPanel diskPanel;
    private DiskQueuePanel diskQueuePanel;
    private JSplitPane diskTab;
    private DiskMetricsPanel diskMetricsPanel;
    private HeadDisplacementChartPanel headChartPanel;

    public MainFrame(Cluster cluster, JTextArea logArea) {
        this(cluster, logArea, (SimulationConfig) null);
    }

    // initialConfig: la configuración con la que se inició, para "Volver a la configuración"
    public MainFrame(Cluster cluster, JTextArea logArea, SimulationConfig initialConfig) {
        this(cluster, logArea, () -> new ConfigFrame(initialConfig).setVisible(true));
    }

    // onBack: acción al pulsar "Volver" (ConfigFrame en Proyecto 1, StartFrame en Proyecto 2)
    public MainFrame(Cluster cluster, JTextArea logArea, Runnable onBack) {
        super("ÁvilaOS - Simulación");
        this.cluster = cluster;
        this.onBack = (onBack != null) ? onBack : () -> new ConfigFrame(null).setVisible(true);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        boolean p2 = cluster.getDisk() != null && cluster.getFileSystem() != null;

        // el reloj llama a los colectores al final de cada ciclo, con todo quieto
        collector = new MetricsCollector(cluster);
        DiskMetricsCollector diskCollector = null;
        if (p2) {
            diskCollector = new DiskMetricsCollector(cluster.getDisk());
            cluster.setAfterTickObserver(new CompositeClockListener(collector, diskCollector));
        } else {
            cluster.setAfterTickObserver(collector);
        }

        int n = cluster.getComputerCount();
        summaryPanel = new SummaryPanel(cluster);
        tabs.addTab("Resumen (todos)", summaryPanel);
        computerPanels = new ComputerPanel[n];
        for (int i = 0; i < n; i++) {
            computerPanels[i] = new ComputerPanel(cluster.getComputer(i).getKernel());
            tabs.addTab("Computador " + i, computerPanels[i]);
        }

        if (p2) {
            fsPanel = new FileSystemPanel(cluster.getFileSystem());
            allocPanel = new AllocationTablePanel(cluster.getFileSystem());
            filesTab = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, fsPanel, allocPanel);
            filesTab.setResizeWeight(0.35);
            tabs.addTab("Archivos", filesTab);

            diskPanel = new DiskPanel(cluster.getDisk(), cluster.getFileSystem().getBlockMap());
            diskQueuePanel = new DiskQueuePanel(cluster.getDisk());
            diskTab = new JSplitPane(JSplitPane.VERTICAL_SPLIT, diskPanel, diskQueuePanel);
            diskTab.setResizeWeight(0.6);
            tabs.addTab("Disco", diskTab);
        }

        metricsPanel = new MetricsPanel(collector, n);
        chartPanel = new UtilizationChartPanel(collector, n);
        tabs.addTab("Métricas", metricsPanel);
        tabs.addTab("Gráfica de utilización", chartPanel);

        if (p2) {
            diskMetricsPanel = new DiskMetricsPanel(diskCollector, cluster.getComputer(0).getKernel());
            headChartPanel = new HeadDisplacementChartPanel(diskCollector);
            tabs.addTab("Métricas del disco", diskMetricsPanel);
            tabs.addTab("Gráfica del cabezal", headChartPanel);
        }

        globalPanel = new GlobalPanel(cluster, logArea);
        globalPanel.setPreferredSize(new Dimension(0, 270));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, globalPanel);
        split.setResizeWeight(0.72);
        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);
        setSize(1250, 920);
        setLocationRelativeTo(null);

        timer = new Timer(REFRESH_MS, e -> refresh());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                timer.stop();
                cluster.stop();
            }
        });
        globalPanel.setOnBackToConfig(this::backToConfig);
        timer.start();
    }

    // Detiene todo (reloj, hilos y refresco) y vuelve a la vista anterior
    private void backToConfig() {
        timer.stop();
        cluster.stop();
        dispose();
        onBack.run();
    }

    private void refresh() {
        globalPanel.refresh();
        Component selected = tabs.getSelectedComponent();
        if (selected == summaryPanel) {
            summaryPanel.refresh();
        } else if (selected == metricsPanel) {
            metricsPanel.refresh();
        } else if (selected == chartPanel) {
            chartPanel.refresh();
        } else if (selected != null && selected == diskTab) {
            diskPanel.refresh();
            diskQueuePanel.refresh();
        } else if (selected != null && selected == diskMetricsPanel) {
            diskMetricsPanel.refresh();
        } else if (selected != null && selected == headChartPanel) {
            headChartPanel.refresh();
        } else {
            for (int i = 0; i < computerPanels.length; i++) {
                if (selected == computerPanels[i]) {
                    computerPanels[i].refresh();
                }
            }
        }
        if (fsPanel != null) {
            fsPanel.refresh();
            allocPanel.refresh();
        }
    }
}