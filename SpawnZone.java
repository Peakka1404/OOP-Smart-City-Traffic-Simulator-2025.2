import java.awt.*;
import java.awt.geom.*;
import java.util.Random;

/**
 * SpawnZone — Vùng sinh/xoá phương tiện.
 *
 * Được đặt tại đầu mút (terminal node) của mạng lưới — tức là nút chỉ nối
 * với đúng một đoạn đường (stub). KHÔNG đặt tại ngã ba/tư/năm.
 *
 * Hình dạng: một đường thẳng vuông góc với đường stub, chiều rộng = chiều rộng
 * toàn bộ một chiều đường (halfWidth * 2).
 *
 * Chỉ sinh xe trên NỬA PHẢI (theo luật tay phải): offset s ∈ [0, halfWidth].
 *
 *          ┌──── spawn line ────┐
 *          │←── halfWidth ──→│
 *     ─────┼─────────────────┼─────  ← Road (stub)
 *          │    right lane   │
 *          └─────────────────┘
 *            Vehicles spawn here
 */
public class SpawnZone {

    private final Node   node;       // terminal node (degree-1)
    private final Road   stubRoad;   // đường stub dẫn vào mạng lưới

    // Vị trí và hướng của đường sinh
    private final double cx, cy;       // tâm = vị trí terminal node
    private final double lineX, lineY; // vector đơn vị dọc theo spawn line (vuông góc với road)
    private final double halfWidth;    // nửa chiều dài đường sinh = road.halfWidth

    // Mỗi SpawnZone đại diện cho cả sinh lẫn xoá:
    // xe đến node này theo đường stub thì được coi là "arrived"

    // Màu hiển thị
    private static final Color COLOR_LINE  = new Color( 60, 220, 100, 200);
    private static final Color COLOR_FILL  = new Color( 60, 220, 100,  40);
    private static final Color COLOR_LABEL = new Color(120, 255, 150, 220);

    // ─────────────────────────────────────────────────────────────────────
    //  Khởi tạo
    // ─────────────────────────────────────────────────────────────────────

    /**
     * @param node     terminal node (endpoint of stub road)
     * @param stubRoad đường stub nối terminal với mạng lưới chính
     */
    public SpawnZone(Node node, Road stubRoad) {
        this.node      = node;
        this.stubRoad  = stubRoad;
        this.cx        = node.getX();
        this.cy        = node.getY();
        this.halfWidth = stubRoad.getHalfWidth();

        // Đường sinh vuông góc với stub → dùng perp vector của stub làm hướng dọc đường
        // (perpX, perpY) của Road đã là vuông góc với dir; spawn line chạy theo hướng đó
        this.lineX = stubRoad.getPerpX();
        this.lineY = stubRoad.getPerpY();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Sinh điểm ngẫu nhiên trên làn phải
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Trả về tọa độ world ngẫu nhiên trên NỬA PHẢI đường sinh.
     * Phương tiện sinh tại đây và lái về hướng mạng lưới.
     *
     * Right lane: s ∈ [halfWidth * 0.05, halfWidth * 0.95]
     */
    public double[] randomSpawnPoint(Random rng) {
        double s      = halfWidth * (0.05 + rng.nextDouble() * 0.90);
        return new double[]{cx + lineX * s, cy + lineY * s};
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Kiểm tra phương tiện đã đến vùng này chưa (cho despawn)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * {@code true} nếu điểm (wx, wy) nằm trong vùng despawn:
     * trong phạm vi ARRIVAL_RADIUS tính từ terminal node.
     */
    public boolean isNear(double wx, double wy) {
        return node.isInsideArrivalZone(wx, wy);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Vẽ spawn zone
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Vẽ đường sinh/xoá lên Graphics2D (đã được áp camera transform).
     */
    public void render(Graphics2D g) {
        // Hai đầu của spawn line
        double x1 = cx - lineX * halfWidth;
        double y1 = cy - lineY * halfWidth;
        double x2 = cx + lineX * halfWidth;
        double y2 = cy + lineY * halfWidth;

        // Chiều dày hình chữ nhật (dọc theo đường stub) = 8px
        double dx  = stubRoad.getDirX() * 6;
        double dy  = stubRoad.getDirY() * 6;

        // Vẽ hình chữ nhật vùng spawn (translucent)
        Path2D poly = new Path2D.Double();
        poly.moveTo(x1 - dx, y1 - dy);
        poly.lineTo(x2 - dx, y2 - dy);
        poly.lineTo(x2 + dx, y2 + dy);
        poly.lineTo(x1 + dx, y1 + dy);
        poly.closePath();

        g.setColor(COLOR_FILL);
        g.fill(poly);

        // Đường kẻ chính (spawn line) - nét đậm
        g.setColor(COLOR_LINE);
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(x1, y1, x2, y2));

        // Hướng xe đi vào (mũi tên nhỏ từ terminal về phía mạng lưới)
        double arrowLen = 10;
        // Hướng vào mạng = hướng từ terminal đến grid node = hướng của stubRoad
        double ax  = stubRoad.getDirX() * arrowLen;
        double ay  = stubRoad.getDirY() * arrowLen;
        // Mũi tên trung điểm đường sinh + lùi về phía terminal
        double midX = cx + ax * 0.3;
        double midY = cy + ay * 0.3;
        g.setColor(new Color(100, 255, 140, 200));
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // Đầu mũi tên
        double headX = midX + ax;
        double headY = midY + ay;
        g.draw(new Line2D.Double(midX, midY, headX, headY));
        double perpA = -ay * 0.35, perpB = ax * 0.35;
        g.draw(new Line2D.Double(headX, headY, headX - ax*0.4 + perpA, headY - ay*0.4 + perpB));
        g.draw(new Line2D.Double(headX, headY, headX - ax*0.4 - perpA, headY - ay*0.4 - perpB));

        // Label
        g.setFont(new Font("Monospaced", Font.BOLD, 9));
        g.setColor(COLOR_LABEL);
        String lbl = "▶ " + node.getId();
        FontMetrics fm = g.getFontMetrics();
        // Đặt label theo hướng vuông góc với spawn line
        float lx = (float)(cx - lineX * halfWidth - dx * 2 - fm.stringWidth(lbl) * 0.5);
        float ly = (float)(cy - lineY * halfWidth - dy * 2 + fm.getAscent() * 0.5);
        g.drawString(lbl, lx, ly);

        g.setStroke(new BasicStroke(1));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Getters
    // ─────────────────────────────────────────────────────────────────────

    public Node getNode()    { return node; }
    public Road getStubRoad(){ return stubRoad; }
    public double getCx()    { return cx; }
    public double getCy()    { return cy; }
}
