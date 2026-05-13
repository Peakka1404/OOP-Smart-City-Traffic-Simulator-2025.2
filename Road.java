/**
 * Road — Con đường một chiều trong không gian 2D.
 *
 * ┌─────────────────────────────────────────────────────┐  ← Lề ngoài TRÁI (left barrier)
 * │  Làn 1          │         Làn 2                     │
 * │  (không có lề   │         giữa — xe chuyển làn tự do│
 * └─────────────────────────────────────────────────────┘  ← Lề ngoài PHẢI (right barrier)
 *   from ──────────────────────────────────────────────▶ to
 *
 * Hệ toạ độ cục bộ của con đường:
 *   t  = khoảng cách dọc theo đường (0 → length), trục "dọc đường"
 *   s  = khoảng cách ngang (−halfWidth → +halfWidth), trục "ngang đường"
 *        s < 0 = làn trái,   s > 0 = làn phải
 *
 * Chỉ hai cạnh ngoài (|s| = halfWidth) là rào chắn (barrier).
 * KHÔNG có rào chắn ở giữa → phương tiện chuyển làn tự do.
 *
 * Va chạm lề đường:
 *   Khi hitbox phương tiện vượt ra ngoài halfWidth, Road đẩy phương tiện
 *   trở lại bên trong và triệt tiêu thành phần vận tốc hướng vào tường.
 */
public class Road {

    // ── Nhận dạng & cấu trúc ────────────────────────────────────────────
    private final String id;
    private final Node   from;
    private final Node   to;
    private final int    laneCount;    // số làn (mặc định 2)
    private final double laneWidth;    // chiều rộng mỗi làn (px)

    // ── Hình học — precomputed ───────────────────────────────────────────
    private final double length;      // chiều dài đường (px)
    private final double halfWidth;   // = laneWidth * laneCount / 2
    private final double dirX;        // vector đơn vị dọc đường (hướng from→to)
    private final double dirY;
    private final double perpX;       // vector đơn vị ngang đường (vuông góc dirX,dirY)
    private final double perpY;       // perpX = -dirY,  perpY = dirX

    // ── Metadata ─────────────────────────────────────────────────────────
    /** Giới hạn tốc độ trên đường này (đơn vị / giây). 0 = không giới hạn. */
    private double speedLimit;

    // ─────────────────────────────────────────────────────────────────────
    //  Khởi tạo
    // ─────────────────────────────────────────────────────────────────────

    /**
     * @param id        định danh, ví dụ "R-AB"
     * @param from      Node xuất phát
     * @param to        Node đích
     * @param laneWidth chiều rộng một làn (> 0)
     * @param laneCount số làn (≥ 1); dùng 2 để xe có thể chuyển làn tự do
     */
    public Road(String id, Node from, Node to, double laneWidth, int laneCount) {
        if (laneWidth <= 0)  throw new IllegalArgumentException("laneWidth phải > 0");
        if (laneCount  < 1)  throw new IllegalArgumentException("laneCount phải ≥ 1");
        if (from == to)      throw new IllegalArgumentException("from và to phải khác nhau");

        this.id        = id;
        this.from      = from;
        this.to        = to;
        this.laneWidth = laneWidth;
        this.laneCount = laneCount;
        this.halfWidth = laneWidth * laneCount / 2.0;

        // Tính vector hướng và vector vuông góc
        double dx   = to.getX() - from.getX();
        double dy   = to.getY() - from.getY();
        this.length = Math.sqrt(dx * dx + dy * dy);
        if (this.length < 1e-6) throw new IllegalArgumentException("from và to quá gần nhau");

        this.dirX  =  dx / length;
        this.dirY  =  dy / length;
        this.perpX = -dirY;   // vuông góc, quay trái 90°
        this.perpY =  dirX;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Chuyển đổi toạ độ  World ↔ Local
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Chuyển toạ độ thế giới (wx, wy) → toạ độ cục bộ [t, s] của đường.
     *   t ∈ [0, length] : vị trí dọc đường
     *   s ∈ [-halfWidth, halfWidth] : vị trí ngang đường
     */
    public double[] worldToLocal(double wx, double wy) {
        double dx = wx - from.getX();
        double dy = wy - from.getY();
        double t  = dx * dirX  + dy * dirY;    // chiếu lên trục dọc
        double s  = dx * perpX + dy * perpY;   // chiếu lên trục ngang
        return new double[]{t, s};
    }

    /**
     * Chuyển toạ độ cục bộ [t, s] → toạ độ thế giới [wx, wy].
     */
    public double[] localToWorld(double t, double s) {
        return new double[]{
            from.getX() + t * dirX + s * perpX,
            from.getY() + t * dirY + s * perpY
        };
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Kiểm tra phương tiện có đang trên đường không
    // ─────────────────────────────────────────────────────────────────────

    /**
     * {@code true} nếu điểm (wx, wy) nằm trong dải đường,
     * kể cả margin mở rộng ở hai đầu (cho vùng giao lộ).
     *
     * @param endMargin khoảng mở rộng ở hai đầu đường (để xe trơn tru qua ngã tư)
     */
    public boolean containsPoint(double wx, double wy, double endMargin) {
        double[] loc = worldToLocal(wx, wy);
        return loc[0] >= -endMargin
            && loc[0] <= length + endMargin
            && Math.abs(loc[1]) <= halfWidth;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Xử lý va chạm lề đường
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Kiểm tra và xử lý va chạm giữa phương tiện {@code v} và hai lề ngoài.
     *
     * <p>Thuật toán:
     * <ol>
     *   <li>Chuyển vị trí tâm xe sang toạ độ cục bộ.</li>
     *   <li>So sánh |s| + hitboxRadius với halfWidth.</li>
     *   <li>Nếu vượt quá: đẩy xe trở lại + triệt tiêu vận tốc ngang hướng ra ngoài.</li>
     * </ol>
     *
     * <p>Không xử lý nếu xe đang ở gần hai đầu đường (vùng giao lộ,
     * được xác định bằng {@code endMargin}).
     *
     * @param v         phương tiện cần kiểm tra
     * @param endMargin khoảng cách tính từ đầu/cuối đường được miễn kiểm tra
     * @return {@code true} nếu có va chạm và đã xử lý
     */
    public boolean resolveBarrierCollision(Vehicle v, double endMargin) {
        double[] loc = worldToLocal(v.getX(), v.getY());
        double t = loc[0];
        double s = loc[1];

        // Bỏ qua nếu xe đang ở vùng đầu/cuối đường (ngã tư)
        if (t < endMargin || t > length - endMargin) return false;

        double limit = halfWidth - v.getHitboxRadius();
        // Đảm bảo limit không âm
        limit = Math.max(limit, 0);

        if (Math.abs(s) <= halfWidth) return false;   // còn trong giới hạn

        // Xác định chiều va chạm và đẩy ngược lại
        double sign    = s > 0 ? 1 : -1;              // +1 = lề phải, -1 = lề trái
        double correction = (Math.abs(s) - halfWidth + v.getHitboxRadius());

        // Đẩy xe trở lại trong toạ độ thế giới
        double newT = t;
        double newS = sign * (halfWidth - v.getHitboxRadius());
        double[] world = localToWorld(newT, newS);
        v.setX(world[0]);
        v.setY(world[1]);

        // Triệt tiêu thành phần vận tốc hướng vào lề
        // Thành phần ngang trong toạ độ cục bộ: vs = vx*perpX + vy*perpY
        double vs = v.getVx() * perpX + v.getVy() * perpY;
        if (sign * vs > 0) {  // vận tốc đang hướng ra ngoài
            // Trừ đi thành phần đó
            v.setVx(v.getVx() - vs * perpX);
            v.setVy(v.getVy() - vs * perpY);
        }

        v.onBarrierHit(this, sign > 0 ? BarrierSide.RIGHT : BarrierSide.LEFT);
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Hình học tiện ích
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Điểm trung tâm tại vị trí t dọc đường (toạ độ thế giới).
     */
    public double[] centerAt(double t) {
        return localToWorld(t, 0);
    }

    /**
     * Tìm điểm trên đường gần nhất với (wx, wy).
     * Trả về [wx_closest, wy_closest].
     */
    public double[] closestPointOnRoad(double wx, double wy) {
        double[] loc = worldToLocal(wx, wy);
        double tClamped = Math.max(0, Math.min(length, loc[0]));
        double sClamped = Math.max(-halfWidth, Math.min(halfWidth, loc[1]));
        return localToWorld(tClamped, sClamped);
    }

    // ── Enum ─────────────────────────────────────────────────────────────
    public enum BarrierSide { LEFT, RIGHT }

    // ─────────────────────────────────────────────────────────────────────
    //  Getters
    // ─────────────────────────────────────────────────────────────────────

    public String getId()        { return id; }
    public Node   getFrom()      { return from; }
    public Node   getTo()        { return to; }
    public double getLength()    { return length; }
    public double getTotalWidth(){ return halfWidth * 2; }
    public double getHalfWidth() { return halfWidth; }
    public double getLaneWidth() { return laneWidth; }
    public int    getLaneCount() { return laneCount; }
    public double getDirX()      { return dirX; }
    public double getDirY()      { return dirY; }
    public double getPerpX()     { return perpX; }
    public double getPerpY()     { return perpY; }
    public double getSpeedLimit(){ return speedLimit; }
    public void setSpeedLimit(double v) { this.speedLimit = v; }

    @Override
    public String toString() {
        return String.format("Road[%s](%s → %s, len=%.0f, lanes=%d×%.0f)",
                id, from.getId(), to.getId(), length, laneCount, laneWidth);
    }
}
