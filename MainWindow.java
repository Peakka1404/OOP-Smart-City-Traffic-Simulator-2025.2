import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * MainWindow — Cửa sổ chính.
 *
 * Lưới 4×3: 12 node nội (ngã tư), 14 stub entry/exit.
 * Mỗi node nội có một IntersectionController với 4 đèn.
 */
public class MainWindow extends JFrame {

    private static final int WIN_W = 1090;
    private static final int WIN_H =  700;

    public MainWindow() {
        super("🚗  Traffic Simulation  —  Traffic Lights + Bezier Turns + Yield");

        RoadNetwork   network  = buildNetwork();
        SoundManager  sound    = new SoundManager();
        SceneRenderer renderer = new SceneRenderer();

        SimulationPanel simPanel  = new SimulationPanel(network, renderer, sound);
        ControlPanel    ctrlPanel = new ControlPanel(simPanel, renderer, sound, network);

        setLayout(new BorderLayout());
        add(simPanel,  BorderLayout.CENTER);
        add(ctrlPanel, BorderLayout.EAST);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setPreferredSize(new Dimension(WIN_W, WIN_H));
        setMinimumSize(new Dimension(750, 520));
        pack();
        setLocationRelativeTo(null);

        SwingUtilities.invokeLater(simPanel::resetCamera);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { sound.shutdown(); }
        });
        simPanel.requestFocusInWindow();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Build road network
    // ─────────────────────────────────────────────────────────────────────

    private RoadNetwork buildNetwork() {
        RoadNetwork net = new RoadNetwork();

        int    cols      = 4, rows = 3;
        double originX   = 120, originY = 110;
        double cellW     = 230, cellH  = 230;
        double laneWidth = 32;      // px/làn; halfWidth = 32*2/2 = 32
        int    laneCount = 2;
        double stubLen   = 75;

        // ── Grid nodes ────────────────────────────────────────────────────
        Node[][] grid = new Node[rows][cols];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                grid[r][c] = new Node("N"+c+r, originX+c*cellW, originY+r*cellH);
                net.addNode(grid[r][c]);
            }

        // ── Internal bidirectional roads ──────────────────────────────────
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols-1; c++)
                net.addBidirectionalRoad("H"+c+r+"E","H"+c+r+"W",
                        grid[r][c], grid[r][c+1], laneWidth, laneCount);
        for (int c = 0; c < cols; c++)
            for (int r = 0; r < rows-1; r++)
                net.addBidirectionalRoad("V"+c+r+"S","V"+c+r+"N",
                        grid[r][c], grid[r+1][c], laneWidth, laneCount);

        // ── IntersectionController cho tất cả 12 grid nodes ──────────────
        double icHalfWidth = laneWidth * laneCount;   // 64px — hộp ngã tư
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                net.addIntersection(grid[r][c], icHalfWidth);

        // ── Stub entry/exit nodes ─────────────────────────────────────────
        // Top
        for (int c = 0; c < cols; c++) {
            Node e = new Node("EN"+c, originX+c*cellW, originY-stubLen); net.addNode(e);
            Road s = new Road("STNS_"+c, e, grid[0][c], laneWidth, laneCount);
            Road n = new Road("STNN_"+c, grid[0][c], e, laneWidth, laneCount);
            net.addRoad(s); net.addRoad(n); net.addSpawnZone(e, s);
        }
        // Bottom
        for (int c = 0; c < cols; c++) {
            Node e = new Node("ES"+c, originX+c*cellW, originY+(rows-1)*cellH+stubLen); net.addNode(e);
            Road s = new Road("STSN_"+c, e, grid[rows-1][c], laneWidth, laneCount);
            Road n = new Road("STSS_"+c, grid[rows-1][c], e, laneWidth, laneCount);
            net.addRoad(s); net.addRoad(n); net.addSpawnZone(e, s);
        }
        // Left
        for (int r = 0; r < rows; r++) {
            Node e = new Node("EW"+r, originX-stubLen, originY+r*cellH); net.addNode(e);
            Road s = new Road("STWE_"+r, e, grid[r][0], laneWidth, laneCount);
            Road w = new Road("STWW_"+r, grid[r][0], e, laneWidth, laneCount);
            net.addRoad(s); net.addRoad(w); net.addSpawnZone(e, s);
        }
        // Right
        for (int r = 0; r < rows; r++) {
            Node e = new Node("EE"+r, originX+(cols-1)*cellW+stubLen, originY+r*cellH); net.addNode(e);
            Road s = new Road("STEW_"+r, e, grid[r][cols-1], laneWidth, laneCount);
            Road es= new Road("STEE_"+r, grid[r][cols-1], e, laneWidth, laneCount);
            net.addRoad(s); net.addRoad(es); net.addSpawnZone(e, s);
        }

        net.setDefaultVehicleSize(11, 20);
        net.setDefaultMaxSpeed(100);
        net.setMaxVehicles(50);

        for (int i = 0; i < 8; i++) net.spawnVehicle();

        System.out.println(net);
        return net;
    }
}
