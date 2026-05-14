import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * MainWindow v5:
 *  • cellW = cellH = 460  (đường gấp đôi so với v4)
 *  • laneWidth = 90, laneCount = 2  → halfWidth = 90px (2 làn × 45px, mỗi làn chứa 3 xe rộng 14px)
 *  • stubLen = 150 (gấp đôi)
 *  • icHalfWidth = 90 = road.halfWidth (stop-line ĐÚNG tại biên ngã tư)
 *  • Vehicle: 14×26px (to hơn để dễ thấy trên đường rộng)
 */
public class MainWindow extends JFrame {

    private static final int WIN_W = 1100;
    private static final int WIN_H =  700;

    public MainWindow() {
        super("🚗  Traffic Simulation v5  —  Proper Traffic Lights + Wide Lanes");

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
        setMinimumSize(new Dimension(750, 500));
        pack();
        setLocationRelativeTo(null);

        SwingUtilities.invokeLater(simPanel::resetCamera);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { sound.shutdown(); }
        });
        simPanel.requestFocusInWindow();
    }

    // ─────────────────────────────────────────────────────────────────────

    private RoadNetwork buildNetwork() {
        RoadNetwork net = new RoadNetwork();

        // ── Thông số ──────────────────────────────────────────────────────
        int    cols      = 4,   rows     = 3;
        double originX   = 200, originY  = 180;

        // Đường dài gấp đôi so với v4
        double cellW     = 460, cellH    = 460;

        // Làn rộng 3 xe (xe rộng 14px × 3 = 42px, +3px margin mỗi bên = ~45px/làn)
        // laneCount=2 → halfWidth = 90*2/2 = 90px; mỗi làn = 45px chứa 3 xe 14px
        double laneWidth = 90;
        int    laneCount = 2;
        // halfWidth = laneWidth * laneCount / 2 = 90

        // Stub gấp đôi
        double stubLen   = 150;

        // ── Grid nodes ────────────────────────────────────────────────────
        Node[][] grid = new Node[rows][cols];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                grid[r][c] = new Node("N"+c+r, originX+c*cellW, originY+r*cellH);
                net.addNode(grid[r][c]);
            }

        // ── Đường nội bộ 2 chiều ──────────────────────────────────────────
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols-1; c++)
                net.addBidirectionalRoad("H"+c+r+"E","H"+c+r+"W",
                        grid[r][c], grid[r][c+1], laneWidth, laneCount);

        for (int c = 0; c < cols; c++)
            for (int r = 0; r < rows-1; r++)
                net.addBidirectionalRoad("V"+c+r+"S","V"+c+r+"N",
                        grid[r][c], grid[r+1][c], laneWidth, laneCount);

        // ── IntersectionController: icHalfWidth = road.halfWidth = 90 ─────
        // Đây là khoảng cách từ tâm ngã tư đến stop-line = ĐÚNG tại biên ngã tư
        double icHalfWidth = laneWidth; // = 90 = halfWidth của road (laneWidth*laneCount/2 = 90)

        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                net.addIntersection(grid[r][c], icHalfWidth);

        // ── Stub entry/exit (terminal nodes) ──────────────────────────────
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

        // ── Config phương tiện ────────────────────────────────────────────
        // Xe 14×26px: rộng hơn để dễ thấy trên đường rộng (3 xe/làn = 45/14 ≈ 3.2 ✓)
        net.setDefaultVehicleSize(14, 26);
        net.setDefaultMaxSpeed(110);
        net.setMaxVehicles(55);

        // Pre-spawn 10 xe
        for (int i = 0; i < 10; i++) net.spawnVehicle();

        System.out.println(net);
        System.out.printf("Road halfWidth=%.0f, icHalfWidth=%.0f, cellSize=%.0f%n",
                laneWidth, icHalfWidth, cellW);
        return net;
    }
}
