import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Node — Nút giao thông (đầu / cuối / giao lộ của các con đường).
 *
 * Mỗi Node là một điểm (x, y) trong không gian 2D thế giới.
 * Các con đường (Road) nối các Node lại với nhau.
 * Node đóng vai trò là đỉnh của đồ thị dùng để tìm đường (A*).
 *
 * Phương tiện sinh ra tại một Node ngẫu nhiên và biến mất khi
 * tới được Node đích.
 */
public class Node {

    // ── Nhận dạng & vị trí ───────────────────────────────────────────────
    private final String id;
    private final double x;   // toạ độ ngang (pixel / đơn vị tùy chọn)
    private final double y;   // toạ độ dọc

    /**
     * Bán kính "vùng đến" (arrival zone).
     * Khi tâm phương tiện cách Node đích < ARRIVAL_RADIUS → coi như đã đến.
     */
    public static final double ARRIVAL_RADIUS = 12.0;

    // ── Đồ thị ───────────────────────────────────────────────────────────
    /** Danh sách đường đi ra từ Node này (cạnh có hướng trong đồ thị). */
    private final List<Road> outgoingRoads = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────
    //  Khởi tạo
    // ─────────────────────────────────────────────────────────────────────

    /**
     * @param id định danh duy nhất, ví dụ "N1", "INTER-A"
     * @param x  toạ độ x trong không gian 2D
     * @param y  toạ độ y trong không gian 2D
     */
    public Node(String id, double x, double y) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id không được trống");
        this.id = id;
        this.x  = x;
        this.y  = y;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Quản lý đường ra
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Gắn một con đường xuất phát từ Node này vào đồ thị.
     * Được gọi bởi {@link RoadNetwork#addRoad(Road)} — không gọi trực tiếp.
     */
    void registerOutgoingRoad(Road road) {
        if (!outgoingRoads.contains(road)) outgoingRoads.add(road);
    }

    /** Danh sách chỉ đọc các đường đi ra. */
    public List<Road> getOutgoingRoads() {
        return Collections.unmodifiableList(outgoingRoads);
    }

    /** {@code true} nếu có ít nhất một đường đi ra. */
    public boolean hasOutgoingRoads() { return !outgoingRoads.isEmpty(); }

    // ─────────────────────────────────────────────────────────────────────
    //  Hình học
    // ─────────────────────────────────────────────────────────────────────

    /** Khoảng cách Euclid đến Node khác. */
    public double distanceTo(Node other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Khoảng cách Euclid đến điểm (wx, wy). */
    public double distanceTo(double wx, double wy) {
        double dx = this.x - wx;
        double dy = this.y - wy;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Kiểm tra xem điểm (wx, wy) có nằm trong "vùng đến" của Node này không.
     * Dùng để phương tiện biết nó đã đến Node đích.
     */
    public boolean isInsideArrivalZone(double wx, double wy) {
        return distanceTo(wx, wy) <= ARRIVAL_RADIUS;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Getters
    // ─────────────────────────────────────────────────────────────────────

    public String getId() { return id; }
    public double getX()  { return x; }
    public double getY()  { return y; }

    @Override
    public String toString() {
        return String.format("Node[%s](%.0f, %.0f)", id, x, y);
    }
}
