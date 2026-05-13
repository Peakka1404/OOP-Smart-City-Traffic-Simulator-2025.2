import java.util.*;

/**
 * Vehicle — Phương tiện hoàn chỉnh:
 *  • Rẽ cong mượt (Bezier) qua ngã tư, không đi qua tâm node
 *  • Phản ứng đèn: dừng ở stop-line đỏ, chậm lại khi vàng
 *  • Nhường đường (yield) theo luật ngã tư
 *  • Giữ khoảng cách với xe trước (followDistance có thể chỉnh)
 *  • Chuyển sang làn trái trước khi rẽ trái
 *  • Luật tay phải: luôn ở nửa phải đường
 */
public class Vehicle {

    public enum State { MOVING, SLOWING, STOPPED, WAITING_LIGHT, YIELDING, ARRIVED }

    private final String id;

    // ── Kinematics ────────────────────────────────────────────────────────
    private double x, y;
    private double angle, targetAngle;
    private double vx, vy, speed;
    private final double maxSpeed, accel, decel;
    private static final double MAX_TURN_RATE = Math.PI * 1.8;  // rad/s

    // ── Hitbox ────────────────────────────────────────────────────────────
    private final double hitW, hitH, hitR;

    // ── Path (nodes) ──────────────────────────────────────────────────────
    private final List<Node> path;
    private int    pathIndex;
    private State  state;
    private boolean arrived;

    // ── Turn path (Bezier waypoints qua ngã tư) ───────────────────────────
    private List<double[]> turnPath   = null;
    private int            turnWpIdx  = 0;
    private static final double TURN_WP_REACH = 4.0;   // px để "đến" turn waypoint
    private static final double TURN_START_MULT = 2.0; // bắt đầu tính turn khi dist < hw * mult

    // ── Road ─────────────────────────────────────────────────────────────
    private Road currentRoad;

    // ── Right-hand / lateral ──────────────────────────────────────────────
    private double targetS = 0, currentS = 0;
    private boolean overtaking  = false;
    private double  overtakeTimer = 0;
    private boolean preparingLeftTurn = false;

    // ── Following distance ────────────────────────────────────────────────
    /** Khoảng cách tối thiểu với xe trước (px). Default = 2/3 chiều dài xe. */
    private double followDistance;
    private static final double FOLLOW_DIST_RATIO = 2.0 / 3.0;

    // ── Traffic light / yield ─────────────────────────────────────────────
    private boolean inIntersectionBox = false;

    // ── Collision ─────────────────────────────────────────────────────────
    private static final double SEP = 1.5, PUSH = 0.5;
    private static final double WP_RADIUS = Node.ARRIVAL_RADIUS;

    // ─────────────────────────────────────────────────────────────────────

    public Vehicle(String id, double sx, double sy,
                   double hitW, double hitH, double maxSpeed, List<Node> path) {
        if (path == null || path.size() < 2)
            throw new IllegalArgumentException("path cần ≥ 2 Node");
        this.id = id; this.x = sx; this.y = sy;
        this.hitW = hitW; this.hitH = hitH; this.hitR = Math.max(hitW,hitH)/2.0;
        this.maxSpeed = maxSpeed; this.accel = maxSpeed*2.2; this.decel = maxSpeed*3.5;
        this.path = path; this.pathIndex = 1;
        this.state = State.MOVING; this.arrived = false;
        this.followDistance = hitH * FOLLOW_DIST_RATIO;
        aimAt(path.get(1));
        this.angle = targetAngle;
        this.speed = maxSpeed * 0.3;
        this.vx = Math.cos(angle)*speed; this.vy = Math.sin(angle)*speed;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Update chính
    // ─────────────────────────────────────────────────────────────────────

    public void update(double dt, List<Vehicle> others, RoadNetwork net) {
        if (arrived) return;
        currentRoad = net.findRoadForVehicle(this);

        // Track xem xe có đang trong hộp ngã tư không
        updateIntersectionBox(net);

        // ── Nếu đang theo turn path (Bezier) ─────────────────────────────
        if (turnPath != null) {
            followTurnPath(dt, others, net);
            return;
        }

        // ── Path bình thường (node to node) ──────────────────────────────
        Node target = path.get(pathIndex);
        double dx = target.getX()-x, dy = target.getY()-y;
        double dist = Math.sqrt(dx*dx+dy*dy);

        // Kiểm tra đến waypoint
        if (dist < WP_RADIUS) {
            pathIndex++;
            if (pathIndex >= path.size()) {
                state = State.ARRIVED; arrived = true; vx=0; vy=0; return;
            }
            target = path.get(pathIndex);
            dx = target.getX()-x; dy = target.getY()-y;
            dist = Math.sqrt(dx*dx+dy*dy);
        }

        // Kiểm tra xem target node có phải ngã tư → chuẩn bị turn path
        IntersectionController ic = net.getIntersectionController(target);
        double hw = currentRoad != null ? currentRoad.getHalfWidth() : 30;

        // Pre-position sang trái nếu sắp rẽ trái
        if (currentRoad != null && ic != null && pathIndex + 1 < path.size()) {
            Road exitRoad = net.findRoadBetween(target, path.get(pathIndex+1));
            if (exitRoad != null) {
                double cross = currentRoad.getDirX()*exitRoad.getDirY()
                             - currentRoad.getDirY()*exitRoad.getDirX();
                preparingLeftTurn = (cross < -0.3) && dist < hw * 4;
            }
        } else {
            preparingLeftTurn = false;
        }

        // Phản ứng đèn giao thông
        double desiredSpeed = reactToLight(ic, currentRoad, net);

        // Bắt đầu turn path khi đủ gần
        if (ic != null && dist < hw * TURN_START_MULT && pathIndex + 1 < path.size()) {
            boolean lightOk = (ic.getLightState(currentRoad) == TrafficLight.LightState.GREEN)
                             || inIntersectionBox;
            // Chỉ tính turn path nếu đèn xanh hoặc đã vào hộp
            if (lightOk && desiredSpeed > 0) {
                Road exitRoad = net.findRoadBetween(target, path.get(pathIndex+1));
                if (exitRoad != null && currentRoad != null) {
                    turnPath = computeTurnPath(currentRoad, exitRoad, target);
                    turnWpIdx = 0;
                    if (turnPath != null && !turnPath.isEmpty()) {
                        followTurnPath(dt, others, net);
                        return;
                    }
                }
            }
        }

        // Yield logic
        if (ic != null && !inIntersectionBox && desiredSpeed > 0) {
            if (ic.shouldYield(this, currentRoad, others)) {
                desiredSpeed = Math.min(desiredSpeed, 0);
                state = State.YIELDING;
            }
        }

        // Khoảng cách với xe trước
        desiredSpeed = applyFollowDistance(desiredSpeed, others);

        // Steer
        steer(target, desiredSpeed, dt);
        if (currentRoad != null) applyLateral(currentRoad, dt, others);

        x += vx*dt; y += vy*dt;

        for (Vehicle o : others) if (o!=this && !o.arrived) resolveVehicleCollision(o);
        if (currentRoad != null) currentRoad.resolveBarrierCollision(this, WP_RADIUS*1.5);

        if (speed > 0.5) angle = Math.atan2(vy, vx);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Rẽ theo Bezier qua ngã tư
    // ─────────────────────────────────────────────────────────────────────

    private void followTurnPath(double dt, List<Vehicle> others, RoadNetwork net) {
        if (turnPath == null || turnWpIdx >= turnPath.size()) {
            // Xong turn path → bỏ qua intersection node
            turnPath = null;
            if (pathIndex < path.size()) pathIndex++;   // skip intersection node
            return;
        }

        double[] wp = turnPath.get(turnWpIdx);
        double dx = wp[0]-x, dy = wp[1]-y;
        double dist = Math.sqrt(dx*dx+dy*dy);

        if (dist < TURN_WP_REACH) {
            turnWpIdx++;
            return;
        }

        // Target angle để turn waypoint
        targetAngle = Math.atan2(dy, dx);

        // Xoay mềm
        double diff = angleDiff(targetAngle, angle);
        double maxT = MAX_TURN_RATE * dt;
        angle += Math.abs(diff) <= maxT ? diff : Math.signum(diff)*maxT;

        // Tốc độ giảm trong ngã tư
        double turningSpeed = maxSpeed * 0.65;
        speed = speed < turningSpeed ? Math.min(turningSpeed, speed+accel*dt)
                                     : Math.max(turningSpeed, speed-decel*dt);
        speed = applyFollowDistance(speed, others);

        vx = Math.cos(angle)*speed;
        vy = Math.sin(angle)*speed;

        x += vx*dt; y += vy*dt;

        for (Vehicle o : others) if (o!=this && !o.arrived) resolveVehicleCollision(o);

        if (speed > 0.5) angle = Math.atan2(vy, vx);
        state = State.MOVING;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Tính Turn Path (Bezier quadratic)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Tính 8 điểm Bezier từ entry point → exit point qua ngã tư.
     *
     * Entry: trên stop-line, đúng làn (phải hoặc trái tùy loại rẽ).
     * Exit : đầu đường ra, làn phải.
     * Control: góc rẽ tương ứng.
     */
    private List<double[]> computeTurnPath(Road entryRoad, Road exitRoad, Node intersection) {
        double hw = entryRoad.getHalfWidth();
        double cx = intersection.getX(), cy = intersection.getY();

        // Xác định loại rẽ bằng cross product
        double cross = entryRoad.getDirX()*exitRoad.getDirY()
                     - entryRoad.getDirY()*exitRoad.getDirX();

        boolean rightTurn  = cross >  0.3;
        boolean leftTurn   = cross < -0.3;

        // Entry: xe ở đâu khi bắt đầu turn
        double entryS = leftTurn ? hw*0.18 : hw*0.68;
        double entryT = Math.max(0, entryRoad.getLength() - hw*0.6);
        double[] P0 = entryRoad.localToWorld(entryT, entryS);

        // Exit: điểm đầu đường ra, làn phải
        double exitS = hw * 0.68;
        double exitT = hw * 0.6;
        double[] P2 = exitRoad.localToWorld(exitT, exitS);

        // Control point
        double[] P1;
        if (Math.abs(cross) < 0.3) {
            // Thẳng: control = điểm giữa đường thẳng từ P0 → P2
            // Dùng điểm trên đường thẳng để tránh cong
            P1 = new double[]{(P0[0]+P2[0])/2, (P0[1]+P2[1])/2};
        } else if (rightTurn) {
            // Rẽ phải: control tại góc trong của rẽ
            double px = entryRoad.getPerpX(), py = entryRoad.getPerpY();
            P1 = new double[]{cx + px*hw*0.7, cy + py*hw*0.7};
        } else {
            // Rẽ trái: cung rộng qua tâm, về phía đường ra
            double fx = exitRoad.getDirX(), fy = exitRoad.getDirY();
            P1 = new double[]{cx + fx*hw*0.4 - entryRoad.getDirX()*hw*0.2,
                              cy + fy*hw*0.4 - entryRoad.getDirY()*hw*0.2};
        }

        // Sample Bezier quadratic tại 10 điểm
        int N = 10;
        List<double[]> pts = new ArrayList<>();
        for (int i = 1; i <= N; i++) {
            double t = (double)i / N;
            double mt = 1-t;
            pts.add(new double[]{
                mt*mt*P0[0] + 2*mt*t*P1[0] + t*t*P2[0],
                mt*mt*P0[1] + 2*mt*t*P1[1] + t*t*P2[1]
            });
        }
        return pts;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Phản ứng đèn
    // ─────────────────────────────────────────────────────────────────────

    private double reactToLight(IntersectionController ic, Road road, RoadNetwork net) {
        if (ic == null || road == null || inIntersectionBox) return maxSpeed;

        TrafficLight.LightState ls = ic.getLightState(road);
        double distToStop = ic.distToStopLine(this, road);

        return switch (ls) {
            case RED -> {
                if (distToStop > 0 && distToStop < road.getHalfWidth() * 3) {
                    state = State.WAITING_LIGHT;
                    yield 0;
                }
                yield maxSpeed;
            }
            case YELLOW -> {
                if (distToStop > 0 && distToStop < road.getHalfWidth() * 4) {
                    state = State.SLOWING;
                    yield maxSpeed * 0.25;
                }
                yield maxSpeed;
            }
            case GREEN -> maxSpeed;
        };
    }

    private void updateIntersectionBox(RoadNetwork net) {
        // Kiểm tra xe có đang ở trong bất kỳ hộp ngã tư nào
        for (IntersectionController ic : net.getAllIntersectionControllers()) {
            double dx = x - ic.getNode().getX(), dy = y - ic.getNode().getY();
            if (Math.sqrt(dx*dx+dy*dy) < ic.getHalfWidth() * 1.2) {
                inIntersectionBox = true;
                return;
            }
        }
        inIntersectionBox = false;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Steer
    // ─────────────────────────────────────────────────────────────────────

    private void steer(Node target, double desiredSpeed, double dt) {
        double dx = target.getX()-x, dy = target.getY()-y;
        double dist = Math.sqrt(dx*dx+dy*dy);
        if (dist > 0.5) aimAt(target);

        double diff = angleDiff(targetAngle, angle);
        double maxT = MAX_TURN_RATE * dt;
        angle += Math.abs(diff) <= maxT ? diff : Math.signum(diff)*maxT;

        // Giảm tốc khi gần node trung gian
        double want = desiredSpeed;
        if (want > 0 && dist < 40 && pathIndex < path.size()-1)
            want = Math.max(want * 0.45, want * dist/40);

        speed = speed < want ? Math.min(want, speed+accel*dt)
                             : Math.max(want, speed-decel*dt);

        vx = Math.cos(angle)*speed;
        vy = Math.sin(angle)*speed;

        if (desiredSpeed == 0) state = State.STOPPED;
        else if (speed < maxSpeed*0.7) state = State.SLOWING;
        else state = State.MOVING;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Khoảng cách xe trước (followDistance)
    // ─────────────────────────────────────────────────────────────────────

    private double applyFollowDistance(double desiredSpeed, List<Vehicle> others) {
        double cosA = Math.cos(angle), sinA = Math.sin(angle);
        Vehicle ahead = null;
        double bestAlong = Double.MAX_VALUE;

        for (Vehicle o : others) {
            if (o==this || o.arrived) continue;
            double ex = o.x-x, ey = o.y-y;
            double along = ex*cosA + ey*sinA;
            double perp  = Math.abs(-ex*sinA + ey*cosA);
            if (along > 0 && along < followDistance*8 && perp < hitW+6 && along < bestAlong) {
                bestAlong = along; ahead = o;
            }
        }
        if (ahead == null) return desiredSpeed;

        double gap = bestAlong - hitR - ahead.hitR;
        if (gap <= 0)                     return 0;
        if (gap < followDistance * 0.5)   return 0;
        if (gap < followDistance)         return Math.min(desiredSpeed, ahead.speed * 0.7);
        if (gap < followDistance * 2)     return Math.min(desiredSpeed, ahead.speed * 0.9);
        return desiredSpeed;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Lateral steering (luật tay phải)
    // ─────────────────────────────────────────────────────────────────────

    private void applyLateral(Road road, double dt, List<Vehicle> others) {
        double[] loc = road.worldToLocal(x, y);
        double t = loc[0], s = loc[1];
        double hw = road.getHalfWidth();
        if (t < 20 || t > road.getLength()-20) return;

        double normalS   = hw * 0.68;
        double passS     = hw * 0.18;
        double leftTurnS = hw * 0.18;

        updateOvertake(others, road, t, dt);
        if (preparingLeftTurn)      targetS = leftTurnS;
        else if (overtaking)        targetS = passS;
        else                        targetS = normalS;

        currentS += (targetS - currentS) * Math.min(1.0, dt*4.0);

        double err = currentS - s;
        double latSpd = Math.max(-maxSpeed*0.35, Math.min(maxSpeed*0.35, err*5.0));
        vx += road.getPerpX() * latSpd;
        vy += road.getPerpY() * latSpd;
    }

    private void updateOvertake(List<Vehicle> others, Road road, double myT, double dt) {
        if (overtaking) { if ((overtakeTimer-=dt) <= 0) overtaking=false; return; }
        for (Vehicle o : others) {
            if (o==this||o.arrived) continue;
            double[] ol = road.worldToLocal(o.x, o.y);
            if (ol[0]-myT > 0 && ol[0]-myT < 80 && Math.abs(ol[1]-currentS) < 16
                    && o.speed < speed*0.82) {
                overtaking=true; overtakeTimer=3.5; return;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Collision
    // ─────────────────────────────────────────────────────────────────────

    public void resolveVehicleCollision(Vehicle o) {
        double dx=x-o.x, dy=y-o.y;
        double dist=Math.sqrt(dx*dx+dy*dy);
        double minD=hitR+o.hitR+SEP;
        if (dist>=minD||dist<1e-6) return;
        double nx=dx/dist, ny=dy/dist;
        double ov=(minD-dist)*PUSH;
        x+=nx*ov; y+=ny*ov; o.x-=nx*ov; o.y-=ny*ov;
        double rvn=(vx-o.vx)*nx+(vy-o.vy)*ny;
        if (rvn<0){ vx-=rvn*nx*PUSH; vy-=rvn*ny*PUSH; o.vx+=rvn*nx*PUSH; o.vy+=rvn*ny*PUSH; }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private void aimAt(Node n) { targetAngle = Math.atan2(n.getY()-y, n.getX()-x); }

    private static double angleDiff(double t, double c) {
        double d=t-c;
        while(d>Math.PI) d-=2*Math.PI;
        while(d<-Math.PI) d+=2*Math.PI;
        return d;
    }

    public double distanceTo(Vehicle o) {
        double dx=x-o.x, dy=y-o.y; return Math.sqrt(dx*dx+dy*dy);
    }

    public double[][] getHitboxCorners() {
        double c=Math.cos(angle), s=Math.sin(angle), hw=hitW/2, hh=hitH/2;
        double[][] lc={{-hw,-hh},{hw,-hh},{hw,hh},{-hw,hh}};
        double[][] wc=new double[4][2];
        for(int i=0;i<4;i++){wc[i][0]=x+lc[i][0]*c-lc[i][1]*s; wc[i][1]=y+lc[i][0]*s+lc[i][1]*c;}
        return wc;
    }

    public void onBarrierHit(Road road, Road.BarrierSide side) { state = State.SLOWING; }

    // ── Getters / Setters ──────────────────────────────────────────────────
    public String   getId()               { return id; }
    public double   getX()                { return x; }
    public double   getY()                { return y; }
    public double   getAngle()            { return angle; }
    public double   getSpeed()            { return speed; }
    public double   getVx()               { return vx; }
    public double   getVy()               { return vy; }
    public double   getHitboxWidth()      { return hitW; }
    public double   getHitboxHeight()     { return hitH; }
    public double   getHitboxRadius()     { return hitR; }
    public double   getMaxSpeed()         { return maxSpeed; }
    public State    getState()            { return state; }
    public boolean  isArrived()           { return arrived; }
    public List<Node> getPath()           { return path; }
    public int      getPathIndex()        { return pathIndex; }
    public Road     getCurrentRoad()      { return currentRoad; }
    public boolean  isOvertaking()        { return overtaking; }
    public boolean  isInIntersectionBox() { return inIntersectionBox; }
    public double   getFollowDistance()   { return followDistance; }
    public Node     getDestination()      { return path.get(path.size()-1); }
    public Node     getOrigin()           { return path.get(0); }
    public Node     getCurrentTarget()    { return pathIndex<path.size() ? path.get(pathIndex) : null; }

    public void setX(double v)            { x=v; }
    public void setY(double v)            { y=v; }
    public void setVx(double v)           { vx=v; }
    public void setVy(double v)           { vy=v; }
    public void setState(State s)         { state=s; }
    public void setCurrentRoad(Road r)    { currentRoad=r; }
    public void setFollowDistance(double d){ followDistance = Math.max(1, d); }
}
