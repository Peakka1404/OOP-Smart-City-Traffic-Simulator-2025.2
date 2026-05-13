import java.util.*;

/**
 * IntersectionController — Điều khiển đèn tại một ngã tư.
 *
 * Pha xen kẽ: Nhóm A (đường song song) xanh ↔ Nhóm B (vuông góc) xanh.
 * Mỗi chuyển pha: đang xanh → vàng → đối diện xanh.
 *
 * Mỗi con đường tiếp cận ngã tư có một TrafficLight riêng.
 *
 * Yield logic:
 *   1. Nhường xe đã ở trong hộp ngã tư.
 *   2. Nếu cùng vào: nhường xe đến từ bên phải mình.
 *   3. Xe rẽ trái nhường xe đi thẳng/rẽ phải từ hướng đối diện.
 */
public class IntersectionController {

    // ── Node ngã tư ───────────────────────────────────────────────────────
    private final Node node;
    private final double halfWidth;   // kích thước hộp ngã tư (px)

    // ── Các đường tiếp cận → đèn tương ứng ───────────────────────────────
    private final Map<Road, TrafficLight> lights = new LinkedHashMap<>();

    // ── Hai nhóm đường: song song / vuông góc ────────────────────────────
    private final List<Road> groupA = new ArrayList<>();  // pha A → xanh trước
    private final List<Road> groupB = new ArrayList<>();  // pha B → xanh sau

    // ── Trạng thái pha ────────────────────────────────────────────────────
    private enum Phase { A_GREEN, A_YELLOW, B_GREEN, B_YELLOW }
    private Phase phase = Phase.A_GREEN;
    private double phaseTimer;

    // Timing toàn bộ đèn (giây) — có thể chỉnh từ UI
    private double greenTime  = 8.0;
    private double yellowTime = 2.5;

    // ─────────────────────────────────────────────────────────────────────

    public IntersectionController(Node node, double halfWidth) {
        this.node      = node;
        this.halfWidth = halfWidth;
        this.phaseTimer = greenTime;
    }

    /**
     * Đăng ký một đường tiếp cận vào ngã tư này.
     * Gọi cho MỌI con đường có to == node (tức là các xe đi vào từ đường đó).
     */
    public void registerApproachRoad(Road road) {
        if (lights.containsKey(road)) return;

        // Vị trí đèn: ngay cạnh stop-line, bên trái của chiều đi
        double[] stopPos = stopLinePos(road);
        double lx = stopPos[1] + road.getPerpX() * (halfWidth + 100000000);
        double ly = stopPos[0] + road.getPerpY() * (halfWidth - 10000000);
        TrafficLight tl = new TrafficLight(road.getId(), lx, ly);
        lights.put(road, tl);

        // Phân vào nhóm: dùng dot product để tìm "đường song song"
        if (groupA.isEmpty()) {
            groupA.add(road);
        } else {
            Road ref = groupA.get(0);
            double dot = Math.abs(ref.getDirX() * road.getDirX() + ref.getDirY() * road.getDirY());
            if (dot > 0.5) groupA.add(road);   // song song (dot ≈ 1)
            else           groupB.add(road);   // vuông góc (dot ≈ 0)
        }
    }

    /** Đồng bộ timing của tất cả đèn và bắt đầu pha A xanh. */
    public void initPhases() {
        for (TrafficLight tl : lights.values()) {
            tl.setGreenTime(greenTime);
            tl.setYellowTime(yellowTime);
            double redT = greenTime + yellowTime;
            tl.setRedTime(redT);
        }
        // Khởi đầu: nhóm A xanh, nhóm B đỏ
        for (Road r : groupA) lights.get(r).forceGreen();
        for (Road r : groupB) {
            TrafficLight tl = lights.get(r);
            if (tl != null) tl.forceRed();
        }
        phaseTimer = greenTime;
        phase = Phase.A_GREEN;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Update
    // ─────────────────────────────────────────────────────────────────────

    public void update(double dt) {
        phaseTimer -= dt;
        for (TrafficLight tl : lights.values()) tl.update(dt);

        if (phaseTimer > 0) return;

        // Chuyển pha
        switch (phase) {
            case A_GREEN -> {
                // A → vàng
                for (Road r : groupA) { TrafficLight t = lights.get(r); if(t!=null) t.forceYellow(); }
                phaseTimer = yellowTime;
                phase = Phase.A_YELLOW;
            }
            case A_YELLOW -> {
                // B → xanh, A → đỏ
                for (Road r : groupA) { TrafficLight t = lights.get(r); if(t!=null) t.forceRed(); }
                for (Road r : groupB) { TrafficLight t = lights.get(r); if(t!=null) t.forceGreen(); }
                phaseTimer = greenTime;
                phase = Phase.B_GREEN;
            }
            case B_GREEN -> {
                for (Road r : groupB) { TrafficLight t = lights.get(r); if(t!=null) t.forceYellow(); }
                phaseTimer = yellowTime;
                phase = Phase.B_YELLOW;
            }
            case B_YELLOW -> {
                for (Road r : groupB) { TrafficLight t = lights.get(r); if(t!=null) t.forceRed(); }
                for (Road r : groupA) { TrafficLight t = lights.get(r); if(t!=null) t.forceGreen(); }
                phaseTimer = greenTime;
                phase = Phase.A_GREEN;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Query
    // ─────────────────────────────────────────────────────────────────────

    /** Lấy trạng thái đèn cho đường tiếp cận `road`. */
    public TrafficLight.LightState getLightState(Road road) {
        TrafficLight tl = lights.get(road);
        return tl == null ? TrafficLight.LightState.GREEN : tl.getState();
    }

    public TrafficLight getLight(Road road) { return lights.get(road); }

    /**
     * Vị trí stop-line (điểm dừng) cho đường `road` trước khi vào ngã tư.
     * = điểm trên đường, cách node halfWidth px.
     */
    public double[] stopLinePos(Road road) {
        // t = length - halfWidth: cách node halfWidth theo chiều road
        double t = road.getLength() - halfWidth;
        t = Math.max(0, t);
        return road.localToWorld(t, 0);   // center của stop-line
    }

    /**
     * Khoảng cách từ xe tới stop-line theo dọc đường.
     * Dương = còn phía trước stop-line; âm = đã qua.
     */
    public double distToStopLine(Vehicle v, Road road) {
        double[] loc = road.worldToLocal(v.getX(), v.getY());
        double t = loc[0];
        double stopT = road.getLength() - halfWidth;
        return stopT - t;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Yield Logic
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Xe `me` có phải nhường đường không?
     *
     * Quy tắc (theo thứ tự ưu tiên):
     *  1. Nhường xe đang ở trong hộp ngã tư (đã vào trước).
     *  2. Nếu xe đối diện đi thẳng/rẽ phải mà mình rẽ trái → nhường.
     *  3. Nếu hai xe cùng lúc vào và đường đi xung đột → nhường xe từ bên phải.
     *
     * @return true = phải nhường (giảm/dừng)
     */
    public boolean shouldYield(Vehicle me, Road myRoad, List<Vehicle> others) {
        double cx = node.getX(), cy = node.getY();
        double boxR = halfWidth * 1.3;   // bán kính hộp ngã tư

        for (Vehicle other : others) {
            if (other == me || other.isArrived()) continue;

            double odx = other.getX() - cx, ody = other.getY() - cy;
            double odist = Math.sqrt(odx*odx + ody*ody);
            boolean otherInBox = odist < boxR;

            // Quy tắc 1: xe khác đã trong hộp → nhường
            if (otherInBox) {
                // Chỉ nhường nếu chúng có đường xung đột
                if (pathsConflict(me, other)) return true;
            }

            // Quy tắc 2 & 3: cả hai đang tiếp cận (chưa vào hộp)
            double mdx = me.getX() - cx, mdy = me.getY() - cy;
            double mdist = Math.sqrt(mdx*mdx + mdy*mdy);
            if (mdist > boxR * 2 || odist > boxR * 2) continue;

            if (!pathsConflict(me, other)) continue;

            // Xe other đến từ bên phải của me?
            double mcos = Math.cos(me.getAngle()), msin = Math.sin(me.getAngle());
            double cross = mcos * ody - msin * odx;
            // cross > 0 = other bên phải (screen coords y-down) → nhường
            if (cross > 0) return true;
        }
        return false;
    }

    /**
     * Hai xe có đường đi xung đột trong ngã tư không?
     * Đơn giản hoá: xung đột nếu góc giữa hai hướng đi < 150° (không cùng chiều).
     */
    private boolean pathsConflict(Vehicle a, Vehicle b) {
        double dot = Math.cos(a.getAngle()) * Math.cos(b.getAngle())
                   + Math.sin(a.getAngle()) * Math.sin(b.getAngle());
        return dot < 0.5;   // góc > 60° = có thể xung đột
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Timing setters (từ ControlPanel)
    // ─────────────────────────────────────────────────────────────────────

    public void setGreenTime(double t) {
        greenTime = Math.max(2, t);
        for (TrafficLight tl : lights.values()) tl.setGreenTime(greenTime);
    }

    public void setYellowTime(double t) {
        yellowTime = Math.max(0.5, t);
        for (TrafficLight tl : lights.values()) tl.setYellowTime(yellowTime);
    }

    public double getGreenTime()  { return greenTime; }
    public double getYellowTime() { return yellowTime; }

    // ─────────────────────────────────────────────────────────────────────
    //  Getters
    // ─────────────────────────────────────────────────────────────────────

    public Node                    getNode()   { return node; }
    public double                  getHalfWidth(){ return halfWidth; }
    public Map<Road, TrafficLight> getLights() { return Collections.unmodifiableMap(lights); }
    public Collection<TrafficLight> getAllLights(){ return lights.values(); }
}
