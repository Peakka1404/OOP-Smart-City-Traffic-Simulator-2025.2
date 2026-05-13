/**
 * TrafficLight — Đèn giao thông cho một làn tiếp cận ngã tư.
 *
 * Ba trạng thái: GREEN → YELLOW → RED → GREEN ...
 * Timing của mỗi màu có thể thay đổi qua setters.
 * Trạng thái được điều khiển bởi IntersectionController (không tự chuyển).
 */
public class TrafficLight {

    public enum LightState { GREEN, YELLOW, RED }

    private LightState state = LightState.RED;

    // Timing (giây) — có thể chỉnh từ UI
    private double greenTime  = 8.0;
    private double yellowTime = 2.5;
    private double redTime    = 10.5;  // = greenTime + yellowTime của pha đối diện

    /** Thời gian còn lại của trạng thái hiện tại. */
    private double timeLeft = redTime;

    // Vị trí render (tâm bóng đèn trên màn hình)
    private double renderX, renderY;

    // ID định danh (ví dụ "N", "S", "E", "W") — dùng để debug
    private final String id;

    // ─────────────────────────────────────────────────────────────────────

    public TrafficLight(String id, double rx, double ry) {
        this.id      = id;
        this.renderX = rx;
        this.renderY = ry;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Gọi bởi IntersectionController mỗi frame
    // ─────────────────────────────────────────────────────────────────────

    public void update(double dt) {
        timeLeft -= dt;
        if (timeLeft <= 0) timeLeft = 0;
    }

    // ── Force state (gọi bởi IntersectionController khi chuyển pha) ───────

    public void forceGreen()  { state = LightState.GREEN;  timeLeft = greenTime;  }
    public void forceYellow() { state = LightState.YELLOW; timeLeft = yellowTime; }
    public void forceRed()    { state = LightState.RED;    timeLeft = redTime;    }

    // ── Query ──────────────────────────────────────────────────────────────

    public boolean isGreen()  { return state == LightState.GREEN; }
    public boolean isYellow() { return state == LightState.YELLOW; }
    public boolean isRed()    { return state == LightState.RED; }

    /** Đèn cho phép xe đi (xanh hoặc đã vào ngã tư trước lúc vàng). */
    public boolean allowsEntry() { return state == LightState.GREEN; }

    public LightState getState()      { return state; }
    public double     getTimeLeft()   { return timeLeft; }
    public double     getRenderX()    { return renderX; }
    public double     getRenderY()    { return renderY; }
    public String     getId()         { return id; }

    // ── Timing setters ────────────────────────────────────────────────────

    public double getGreenTime()  { return greenTime; }
    public double getYellowTime() { return yellowTime; }
    public double getRedTime()    { return redTime; }

    public void setGreenTime(double t)  { greenTime  = Math.max(1, t); }
    public void setYellowTime(double t) { yellowTime = Math.max(0.5, t); }
    /** redTime tự tính: bằng greenTime + yellowTime của pha đối */
    public void setRedTime(double t)    { redTime = Math.max(1, t); }

    public void setRenderPos(double x, double y) { renderX = x; renderY = y; }

    @Override public String toString() {
        return "TL[" + id + "] " + state + " " + String.format("%.1f", timeLeft) + "s";
    }
}
