import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * MainWindow — Cửa sổ chính hỗ trợ hai chế độ:
 *  • Normal  : mô phỏng giao thông trên mạng mặc định
 *  • Module  : chỉnh sửa mạng lưới bằng kéo-thả, sau đó chạy mô phỏng
 */
public class MainWindow extends JFrame {

    private static final int WIN_W = 1100;
    private static final int WIN_H =  720;

    public MainWindow(boolean moduleMode) {
        super(moduleMode ? "🔧  Traffic Simulation — Chế Độ Module"
                        : "🚗  Traffic Simulation — Chế Độ Thường");

        RoadNetwork network = buildNetwork();

        if (moduleMode) buildModuleUI(network);
        else            buildNormalUI(network);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setPreferredSize(new Dimension(WIN_W, WIN_H));
        setMinimumSize(new Dimension(750, 500));
        pack();
        setLocationRelativeTo(null);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Normal mode
    // ─────────────────────────────────────────────────────────────────────

    private void buildNormalUI(RoadNetwork network) {
        SoundManager  sound    = new SoundManager();
        SceneRenderer renderer = new SceneRenderer();

        SimulationPanel simPanel  = new SimulationPanel(network, renderer, sound);
        ControlPanel    ctrlPanel = new ControlPanel(simPanel, renderer, sound, network);

        setLayout(new BorderLayout());
        add(simPanel,  BorderLayout.CENTER);
        add(ctrlPanel, BorderLayout.EAST);

        SwingUtilities.invokeLater(simPanel::resetCamera);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { sound.shutdown(); }
        });
        simPanel.requestFocusInWindow();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Module mode
    // ─────────────────────────────────────────────────────────────────────

    private void buildModuleUI(RoadNetwork network) {
        setLayout(new BorderLayout());

        // Référence au panneau courant (éditeur ou simulation)
        JPanel[] currentCenter = {null};

        // ── Créer l'éditeur ───────────────────────────────────────────────
        ModuleEditorPanel editor = new ModuleEditorPanel(network, () -> {
            // "Bắt Đầu Mô Phỏng" clicked in editor
            launchSimulation(network, currentCenter);
        });

        // Toolbar de l'éditeur (en haut)
        JPanel toolbar = editor.buildToolbar();

        // Layout initial: toolbar top + editor center
        add(toolbar, BorderLayout.NORTH);
        add(editor,  BorderLayout.CENTER);
        currentCenter[0] = editor;

        SwingUtilities.invokeLater(editor::fitCamera);
        editor.requestFocusInWindow();
    }

    /**
     * Lance la simulation sur le réseau édité.
     * Remplace l'éditeur par le panneau de simulation dans la même fenêtre.
     */
    private void launchSimulation(RoadNetwork network, JPanel[] currentCenter) {
        // Nettoyer les véhicules existants
        network.clearVehicles();

        SoundManager  sound    = new SoundManager();
        SceneRenderer renderer = new SceneRenderer();

        SimulationPanel simPanel  = new SimulationPanel(network, renderer, sound);
        ControlPanel    ctrlPanel = new ControlPanel(simPanel, renderer, sound, network);

        // Enlever le contenu actuel et reconstruire
        getContentPane().removeAll();
        setLayout(new BorderLayout());

        // Toolbar avec bouton "Retour en mode Module"
        JPanel topBar = buildSimToolbar(network, currentCenter, sound, simPanel);
        add(topBar,    BorderLayout.NORTH);
        add(simPanel,  BorderLayout.CENTER);
        add(ctrlPanel, BorderLayout.EAST);

        revalidate(); repaint();
        SwingUtilities.invokeLater(simPanel::resetCamera);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { sound.shutdown(); }
        });
        simPanel.requestFocusInWindow();
    }

    private JPanel buildSimToolbar(RoadNetwork network, JPanel[] currentCenter,
                                    SoundManager sound, SimulationPanel simPanel) {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        bar.setBackground(new Color(28, 32, 40));
        bar.setBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(50,55,65)));

        JLabel lbl = new JLabel("  ▶  Mô Phỏng  |");
        lbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        lbl.setForeground(new Color(39,174,96)); bar.add(lbl);

        JButton btnBack = new JButton("◀  Quay Lại Editor");
        btnBack.setBackground(new Color(52, 73, 94)); btnBack.setForeground(Color.WHITE);
        btnBack.setFont(new Font("SansSerif", Font.BOLD, 11)); btnBack.setFocusPainted(false);
        btnBack.setBorderPainted(false); btnBack.setOpaque(true);
        btnBack.setBorder(BorderFactory.createEmptyBorder(5,10,5,10));
        btnBack.addActionListener(e -> {
            sound.shutdown();
            network.clearVehicles();
            getContentPane().removeAll();

            // Recréer l'éditeur avec le même réseau
            ModuleEditorPanel editor = new ModuleEditorPanel(network, () ->
                launchSimulation(network, new JPanel[]{null}));
            JPanel toolbar = editor.buildToolbar();
            setLayout(new BorderLayout());
            add(toolbar, BorderLayout.NORTH);
            add(editor,  BorderLayout.CENTER);
            revalidate(); repaint();
            SwingUtilities.invokeLater(editor::fitCamera);
            editor.requestFocusInWindow();
        });
        bar.add(btnBack);
        return bar;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Build road network
    // ─────────────────────────────────────────────────────────────────────

    private RoadNetwork buildNetwork() {
        RoadNetwork net = new RoadNetwork();

        int    cols=4, rows=3;
        double originX=200, originY=180;
        double cellW=460, cellH=460;
        double laneWidth=90;
        int    laneCount=2;
        double stubLen=150;

        // Params pour l'éditeur
        net.setEditorParams(cellW, laneWidth, laneCount);

        // Grid nodes
        Node[][] grid = new Node[rows][cols];
        for (int r=0;r<rows;r++)
            for (int c=0;c<cols;c++) {
                grid[r][c] = new Node("N"+c+r, originX+c*cellW, originY+r*cellH);
                net.addNode(grid[r][c]);
            }

        // Roads
        for (int r=0;r<rows;r++)
            for (int c=0;c<cols-1;c++)
                net.addBidirectionalRoad("H"+c+r+"E","H"+c+r+"W",
                        grid[r][c],grid[r][c+1],laneWidth,laneCount);
        for (int c=0;c<cols;c++)
            for (int r=0;r<rows-1;r++)
                net.addBidirectionalRoad("V"+c+r+"S","V"+c+r+"N",
                        grid[r][c],grid[r+1][c],laneWidth,laneCount);

        // Intersections
        double icHW = laneWidth;
        for (int r=0;r<rows;r++)
            for (int c=0;c<cols;c++)
                net.addIntersection(grid[r][c], icHW);

        // Stubs + SpawnZones
        for (int c=0;c<cols;c++){
            Node e=new Node("EN"+c,originX+c*cellW,originY-stubLen); net.addNode(e);
            Road s=new Road("STNS_"+c,e,grid[0][c],laneWidth,laneCount);
            Road n=new Road("STNN_"+c,grid[0][c],e,laneWidth,laneCount);
            net.addRoad(s);net.addRoad(n);net.addSpawnZone(e,s);
        }
        for (int c=0;c<cols;c++){
            Node e=new Node("ES"+c,originX+c*cellW,originY+(rows-1)*cellH+stubLen); net.addNode(e);
            Road s=new Road("STSN_"+c,e,grid[rows-1][c],laneWidth,laneCount);
            Road n=new Road("STSS_"+c,grid[rows-1][c],e,laneWidth,laneCount);
            net.addRoad(s);net.addRoad(n);net.addSpawnZone(e,s);
        }
        for (int r=0;r<rows;r++){
            Node e=new Node("EW"+r,originX-stubLen,originY+r*cellH); net.addNode(e);
            Road s=new Road("STWE_"+r,e,grid[r][0],laneWidth,laneCount);
            Road w=new Road("STWW_"+r,grid[r][0],e,laneWidth,laneCount);
            net.addRoad(s);net.addRoad(w);net.addSpawnZone(e,s);
        }
        for (int r=0;r<rows;r++){
            Node e=new Node("EE"+r,originX+(cols-1)*cellW+stubLen,originY+r*cellH); net.addNode(e);
            Road s=new Road("STEW_"+r,e,grid[r][cols-1],laneWidth,laneCount);
            Road es=new Road("STEE_"+r,grid[r][cols-1],e,laneWidth,laneCount);
            net.addRoad(s);net.addRoad(es);net.addSpawnZone(e,s);
        }

        net.setDefaultVehicleSize(14,26);
        net.setDefaultMaxSpeed(110);
        net.setMaxVehicles(55);

        System.out.println(net);
        return net;
    }
}
