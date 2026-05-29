import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.*;

/**
 * SimulationPanel — Canvas chính.
 *
 * Camera controls:
 *   • Scroll wheel     → zoom in / out (tâm tại con trỏ)
 *   • Drag phải / giữa → pan
 *   • Double-click     → reset camera
 *
 * Keyboard:
 *   Space → pause/resume   R → reset   S → spawn 1 xe
 *
 * Click trái gần Node → spawn xe tại node đó
 */
public class SimulationPanel extends JPanel {

    private static final int FPS      = 60;
    private static final int TIMER_MS = 1000 / FPS;

    // ── Domain ────────────────────────────────────────────────────────────
    private final RoadNetwork  network;
    private final SceneRenderer renderer;
    private final SoundManager  sound;

    // ── Camera ────────────────────────────────────────────────────────────
    private double camX    = 0, camY = 0;  // world coords of screen centre
    private double camZoom = 1.0;
    private static final double ZOOM_MIN = 0.15;
    private static final double ZOOM_MAX = 6.0;
    private static final double ZOOM_STEP= 0.12;

    // Pan state
    private Point  dragStart  = null;
    private double camXAtDrag = 0, camYAtDrag = 0;

    // ── Game loop ────────────────────────────────────────────────────────
    private final javax.swing.Timer gameTimer;
    private long  lastNano  = System.nanoTime();
    private boolean paused  = false;

    // ── FPS / stats ───────────────────────────────────────────────────────
    private int fpsDisplay = 0, fpsCount = 0;
    private long fpsAccum  = 0;

    // ── Spawn ─────────────────────────────────────────────────────────────
    private double spawnAccum    = 0;
    private double spawnInterval = 1.5;
    private boolean autoSpawn    = true;

    // ── Stats ─────────────────────────────────────────────────────────────
    int totalSpawned = 0, totalArrived = 0;

    // ── Off-screen buffer ────────────────────────────────────────────────
    private BufferedImage buffer;
    private double engineAccum = 0;

    // ─────────────────────────────────────────────────────────────────────

    public SimulationPanel(RoadNetwork network, SceneRenderer renderer, SoundManager sound) {
        this.network  = network;
        this.renderer = renderer;
        this.sound    = sound;
        setBackground(new Color(38, 42, 48));
        setFocusable(true);

        setupMouse();
        setupKeyboard();

        gameTimer = new javax.swing.Timer(TIMER_MS, e -> tick());
        gameTimer.start();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Game loop
    // ─────────────────────────────────────────────────────────────────────

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min((now - lastNano) / 1e9, 0.05);
        lastNano = now;

        fpsCount++;
        fpsAccum += (long)(dt * 1000);
        if (fpsAccum >= 1000) { fpsDisplay = fpsCount; fpsCount = 0; fpsAccum = 0; }

        int before = network.getVehicleCount();
        network.update(dt);
        int arrived = Math.max(0, before - network.getVehicleCount());
        totalArrived += arrived;
        if (arrived > 0) sound.playArrive();

        engineAccum += dt;
        if (engineAccum > 0.25) {
            engineAccum = 0;
            var vlist = network.getVehicles();
            if (!vlist.isEmpty()) {
                double avg = vlist.stream().mapToDouble(Vehicle::getSpeed).average().orElse(0);
                double mx  = network.getDefaultMaxSpeed();
                sound.playEngine(mx > 0 ? avg/mx : 0);
            }
        }

        if (autoSpawn) {
            spawnAccum += dt;
            if (spawnAccum >= spawnInterval) {
                spawnAccum = 0;
                Vehicle v = network.spawnVehicle();
                if (v != null) totalSpawned++;
            }
        }
        repaint();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Rendering
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h)
            buffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        Graphics2D g2 = buffer.createGraphics();

        // ── Background ────────────────────────────────────────────────────
        g2.setColor(new Color(28, 34, 28));
        g2.fillRect(0, 0, w, h);

        // ── Apply camera transform ─────────────────────────────────────────
        AffineTransform saved = g2.getTransform();
        g2.translate(w / 2.0, h / 2.0);
        g2.scale(camZoom, camZoom);
        g2.translate(-camX, -camY);

        // ── Render world ──────────────────────────────────────────────────
        renderer.render(g2, network);

        // ── Restore transform for HUD ──────────────────────────────────────
        g2.setTransform(saved);
        drawHUD(g2, w, h);

        g2.dispose();
        g.drawImage(buffer, 0, 0, null);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HUD (screen-space, không bị camera ảnh hưởng)
    // ─────────────────────────────────────────────────────────────────────

    private void drawHUD(Graphics2D g, int w, int h) {
        // ── Status bar bottom ──────────────────────────────────────────────
        g.setColor(new Color(0,0,0,165));
        g.fillRect(0, h-26, w, 26);
        g.setFont(new Font("Monospaced", Font.PLAIN, 11));
        g.setColor(new Color(170,210,170));
        g.drawString(String.format(
            "FPS:%d  Xe:%d  Sinh:%d  Đến:%d  Zoom:%.2fx  [Scroll]Zoom  [Drag-P/M]Pan  [DblClick]Reset  [Space]Pause  [R]Reset  [S]Spawn",
            fpsDisplay, network.getVehicleCount(), totalSpawned, totalArrived, camZoom),
            8, h-8);

        // ── Hint nếu đang pause ────────────────────────────────────────────
        if (paused) {
            g.setFont(new Font("SansSerif", Font.BOLD, 22));
            g.setColor(new Color(255,200,80,220));
            String p = "⏸  TẠM DỪNG  —  nhấn Space để tiếp tục";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(p, (w - fm.stringWidth(p))/2, h/2 - 10);
        }

        // ── Legend (top-left) ──────────────────────────────────────────────
        Object[][] legend = {
            {new Color(52,152,219),  "Đang chạy"},
            {new Color(230,126, 34), "Giảm tốc" },
            {new Color(192, 57, 43), "Dừng lại" },
            {new Color(155, 89,182), "Đang vượt"},
        };
        int lx = 10, ly = 12;
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        for (Object[] row : legend) {
            g.setColor((Color)row[0]);
            g.fillRoundRect(lx, ly, 11, 11, 3, 3);
            g.setColor(new Color(210,210,210));
            g.drawString((String)row[1], lx+15, ly+9);
            ly += 15;
        }

        // ── Zoom indicator (top-right) ─────────────────────────────────────
        String zm = String.format("%.1f×", camZoom);
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.setColor(new Color(120,180,255,200));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(zm, w - fm.stringWidth(zm) - 10, 20);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Camera utilities
    // ─────────────────────────────────────────────────────────────────────

    /** Chuyển toạ độ screen → world. */
    private double[] screenToWorld(int sx, int sy) {
        int w = getWidth(), h = getHeight();
        return new double[]{
            (sx - w/2.0) / camZoom + camX,
            (sy - h/2.0) / camZoom + camY
        };
    }

    /** Zoom vào/ra xung quanh điểm screen (sx, sy). */
    private void zoomAt(int sx, int sy, double factor) {
        double[] before = screenToWorld(sx, sy);
        camZoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, camZoom * factor));
        // Giữ điểm dưới con trỏ cố định
        double[] after = screenToWorld(sx, sy);
        camX -= (after[0] - before[0]);
        camY -= (after[1] - before[1]);
    }

    /** Đặt camera nhìn vào bounding box của mạng lưới. */
    public void resetCamera() {
        var nodes = network.getNodes();
        if (nodes.isEmpty()) { camX=0; camY=0; camZoom=1; return; }
        double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,
               maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE;
        for (Node n : nodes) {
            minX=Math.min(minX,n.getX()); maxX=Math.max(maxX,n.getX());
            minY=Math.min(minY,n.getY()); maxY=Math.max(maxY,n.getY());
        }
        camX = (minX+maxX)/2; camY = (minY+maxY)/2;
        double netW = maxX-minX+200, netH = maxY-minY+200;
        camZoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX,
                  Math.min(getWidth()/netW, getHeight()/netH)));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Mouse
    // ─────────────────────────────────────────────────────────────────────

    private void setupMouse() {
        // ── Scroll: zoom at cursor ─────────────────────────────────────────
        addMouseWheelListener(e -> {
            double factor = e.getWheelRotation() < 0 ? 1+ZOOM_STEP : 1-ZOOM_STEP;
            // Shift+scroll: pan horizontal
            if (e.isShiftDown()) { camX += e.getWheelRotation() * 20/camZoom; }
            else                 { zoomAt(e.getX(), e.getY(), factor); }
            repaint();
        });

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                // Pan: phải hoặc giữa
                if (e.getButton()==MouseEvent.BUTTON3 || e.getButton()==MouseEvent.BUTTON2) {
                    dragStart = e.getPoint(); camXAtDrag = camX; camYAtDrag = camY;
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (e.getButton()==MouseEvent.BUTTON3 || e.getButton()==MouseEvent.BUTTON2)
                    dragStart = null;
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragStart != null) {
                    double dx = (e.getX() - dragStart.x) / camZoom;
                    double dy = (e.getY() - dragStart.y) / camZoom;
                    camX = camXAtDrag - dx;
                    camY = camYAtDrag - dy;
                    repaint();
                }
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getButton()==MouseEvent.BUTTON1) {
                    if (e.getClickCount()==2) { resetCamera(); return; }
                    // Click trái đơn → spawn tại Node gần nhất
                    double[] w = screenToWorld(e.getX(), e.getY());
                    Node nearest = null; double minD = 25/camZoom;
                    for (Node n : network.getNodes()) {
                        double d = n.distanceTo(w[0], w[1]);
                        if (d < minD) { minD=d; nearest=n; }
                    }
                    if (nearest != null) spawnFromNode(nearest);
                    else { Vehicle v = network.spawnVehicle(); if(v!=null){totalSpawned++;sound.playSignal();} }
                }
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Keyboard
    // ─────────────────────────────────────────────────────────────────────

    private void setupKeyboard() {
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_SPACE -> togglePause();
                    case KeyEvent.VK_R     -> resetSim();
                    case KeyEvent.VK_S     -> spawnOne();
                    case KeyEvent.VK_F     -> resetCamera();   // F = fit view
                    // WASD / arrow keys: pan thủ công
                    case KeyEvent.VK_LEFT,  KeyEvent.VK_A -> camX -= 30/camZoom;
                    case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> camX += 30/camZoom;
                    case KeyEvent.VK_UP,    KeyEvent.VK_W -> camY -= 30/camZoom;
                    case KeyEvent.VK_DOWN,  KeyEvent.VK_V -> camY += 30/camZoom;
                    case KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS  ->
                            zoomAt(getWidth()/2, getHeight()/2, 1+ZOOM_STEP*2);
                    case KeyEvent.VK_MINUS ->
                            zoomAt(getWidth()/2, getHeight()/2, 1-ZOOM_STEP*2);
                }
                repaint();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Spawn helpers
    // ─────────────────────────────────────────────────────────────────────

    private void spawnFromNode(Node startNode) {
        var nodeList = new java.util.ArrayList<>(network.getNodes());
        java.util.Collections.shuffle(nodeList, new Random());
        for (Node dest : nodeList) {
            if (dest == startNode) continue;
            java.util.List<Node> path = network.findPath(startNode, dest);
            if (path != null && path.size() >= 2) {
                String id = String.format("V%03d", (int)(Math.random()*999));
                Vehicle v = new Vehicle(id, startNode.getX(), startNode.getY(),
                        network.getDefaultVehicleWidth(), network.getDefaultVehicleHeight(),
                        network.getDefaultMaxSpeed(), path);
                network.addVehicle(v); totalSpawned++; sound.playSignal(); return;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Public API cho ControlPanel
    // ─────────────────────────────────────────────────────────────────────

    public void togglePause() {
        paused = !paused;
        if (paused) gameTimer.stop();
        else { gameTimer.start(); lastNano = System.nanoTime(); }
    }
    public void resetSim()  { network.clearVehicles(); totalSpawned=0; totalArrived=0; spawnAccum=0; }
    public void spawnOne()  { Vehicle v=network.spawnVehicle(); if(v!=null){totalSpawned++;sound.playSignal();} }

    public void setSpawnInterval(double s)  { spawnInterval = s; }
    public void setAutoSpawn(boolean b)     { autoSpawn = b; }
    public boolean isPaused()               { return paused; }
    public boolean isAutoSpawn()            { return autoSpawn; }
    public int  getTotalSpawned()           { return totalSpawned; }
    public int  getTotalArrived()           { return totalArrived; }
    public int  getFps()                    { return fpsDisplay; }
    public double getCamZoom()              { return camZoom; }
}
