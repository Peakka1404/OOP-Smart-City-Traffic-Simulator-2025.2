import java.util.*;

/**
 * Vehicle v6 — Sửa các lỗi:
 *  1. computeTurnPath dùng CUBIC BEZIER với tiếp tuyến chính xác tại entry/exit
 *     → xe không quay vòng khi rẽ phải, hoạt động đúng với MỌI kích thước đường.
 *  2. Không áp dụng lateral steering khi đang WAITING_LIGHT hoặc tốc độ < 5 px/s
 *     → xe đi thẳng và chỉ phanh trước vạch dừng, không lắc trái-phải.
 *  3. Tốc độ tự tăng trên đoạn đường dài thẳng, giảm khi gần ngã tư/rẽ.
 *  4. accel/decel có thể chỉnh từ ControlPanel.
 */

public class Vehicle {

    public enum State { MOVING, SLOWING, STOPPED, WAITING_LIGHT, YIELDING, ARRIVED }
    private final String type;
    private final String id;
    

    // ── Kinematics ────────────────────────────────────────────────────────
    private double x, y, angle, targetAngle;
    private double vx, vy, speed;
    private final double maxSpeed;
    private double accel, decel;           // mutable: có thể chỉnh từ UI
    private static final double MAX_TURN_RATE = Math.PI * 2.2;  // rad/s

    // ── Hitbox ────────────────────────────────────────────────────────────
    private final double hitW, hitH, hitR;

    // ── Path ──────────────────────────────────────────────────────────────
    private final List<Node> path;
    private int    pathIndex;
    private State  state;
    private boolean arrived;

    // ── Turn path (cubic Bezier waypoints qua ngã tư) ─────────────────────
    private List<double[]> turnPath  = null;
    private int            turnWpIdx = 0;
    private static final double TURN_WP_REACH   = 6.0;
    private static final double TURN_START_MULT = 1.1; // dist < hw*1.1 → tính turn

    // ── Road ─────────────────────────────────────────────────────────────
    private Road currentRoad;

    // ── Right-hand lateral (KHÔNG dùng khi đang phanh/chờ đèn) ──────────
    private double targetS = 0, currentS = 0;
    private boolean overtaking = false, preparingLeftTurn = false;
    private double  overtakeTimer = 0;

    // ── Follow distance ───────────────────────────────────────────────────
    private double followDistance;

    // ── Intersection box ─────────────────────────────────────────────────
    private boolean inIntersectionBox = false;

    // ── Road direction locking (để không lắc khi phanh) ──────────────────
    private double lockedRoadAngle = Double.NaN;  // locked angle khi phanh

    private static final double SEP  = 1.5, PUSH = 0.5;
    private static final double WP_R = Node.ARRIVAL_RADIUS;
    

    // ─────────────────────────────────────────────────────────────────────

    public Vehicle(String id, String type, double sx, double sy,
               double hitW, double hitH, double maxSpeed, List<Node> path) {
        if (path == null || path.size() < 2) throw new IllegalArgumentException("path ≥ 2 Node");
        this.id = id;this.type = type; this.x = sx; this.y = sy;
        this.hitW = hitW; this.hitH = hitH; this.hitR = Math.max(hitW,hitH)/2.0;
        this.maxSpeed = maxSpeed;
        this.accel    = maxSpeed * 2.2;
        this.decel    = maxSpeed * 3.5;
        this.path = path; this.pathIndex = 1;
        this.state = State.MOVING; this.arrived = false;
        this.followDistance = hitH * (2.0/3.0);
        aimAt(path.get(1));
        this.angle = targetAngle;
        this.speed = maxSpeed * 0.25;
        this.vx = Math.cos(angle)*speed; this.vy = Math.sin(angle)*speed;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Update chính
    // ─────────────────────────────────────────────────────────────────────

    public void update(double dt, List<Vehicle> others, RoadNetwork net) {
        if (arrived) return;
        currentRoad = net.findRoadForVehicle(this);
        updateIntersectionBox(net);

        // ── Đang theo turn path ───────────────────────────────────────────
        if (turnPath != null) { followTurnPath(dt, others); return; }

        // ── Bình thường ───────────────────────────────────────────────────
        Node target = path.get(pathIndex);
        double dx = target.getX()-x, dy = target.getY()-y;
        double dist = Math.sqrt(dx*dx+dy*dy);

        if (dist < WP_R) {
            if (++pathIndex >= path.size()) {
                state = State.ARRIVED; arrived = true; vx=0; vy=0; return;
            }
            target = path.get(pathIndex);
            dx = target.getX()-x; dy = target.getY()-y;
            dist = Math.sqrt(dx*dx+dy*dy);
        }

        IntersectionController ic = net.getIntersectionController(target);
        double hw = currentRoad != null ? currentRoad.getHalfWidth() : 80;

        // Chuẩn bị rẽ trái
        updateLeftTurnPrep(ic, hw, dist, net);

        // Tốc độ mong muốn từ đèn
        double desiredSpeed = reactToLight(ic, currentRoad, hw);

        // Kích hoạt turn path
        boolean frontPastStop = isFrontPastStopLine(ic, currentRoad);
        boolean lightOk = frontPastStop || ic == null
                || ic.getLightState(currentRoad) == TrafficLight.LightState.GREEN;

        if (lightOk && desiredSpeed > 0 && dist < hw * TURN_START_MULT
                && pathIndex + 1 < path.size()) {
            Road exitRoad = net.findRoadBetween(target, path.get(pathIndex+1));
            if (exitRoad != null && currentRoad != null) {
                turnPath = computeTurnPath(currentRoad, exitRoad, target);
                turnWpIdx = 0;
                if (turnPath != null && !turnPath.isEmpty()) {
                    lockedRoadAngle = Double.NaN;
                    followTurnPath(dt, others); return;
                }
            }
        }

        // Yield
        if (ic != null && !inIntersectionBox && desiredSpeed > 0) {
            if (ic.shouldYield(this, currentRoad, others)) {
                desiredSpeed = 0; state = State.YIELDING;
            }
        }

        // Follow distance
        desiredSpeed = applyFollowDistance(desiredSpeed, others);

        // Steer
        steer(target, desiredSpeed, dt);

        // Lateral steering: KHÔNG áp dụng khi đang chờ đèn hoặc tốc độ thấp
        boolean canSteerLaterally = (state != State.WAITING_LIGHT)
                && (state != State.STOPPED)
                && (speed > 4)
                && !inIntersectionBox;

        if (currentRoad != null && canSteerLaterally) {
            applyLateral(currentRoad, dt, others);
        } else if (currentRoad != null && !canSteerLaterally) {
            // Khóa hướng theo đường để không lắc
            double roadAngle = Math.atan2(currentRoad.getDirY(), currentRoad.getDirX());
            if (!Double.isNaN(roadAngle)) {
                lockedRoadAngle = roadAngle;
                angle = lerpAngle(angle, roadAngle, Math.min(1.0, dt * 6));
                vx = Math.cos(angle) * speed;
                vy = Math.sin(angle) * speed;
            }
        }

        x += vx*dt; y += vy*dt;
        for (Vehicle o : others) if (o!=this && !o.arrived) resolveVehicleCollision(o);
        if (currentRoad != null) currentRoad.resolveBarrierCollision(this, WP_R*1.5);
        if (speed > 1.5 && canSteerLaterally) angle = Math.atan2(vy, vx);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Turn path theo Bezier CUBIC (sửa lỗi quay vòng)
    // ─────────────────────────────────────────────────────────────────────

    private void followTurnPath(double dt, List<Vehicle> others) {
        if (turnPath == null || turnWpIdx >= turnPath.size()) {
            turnPath = null;
            if (pathIndex < path.size()) pathIndex++;
            return;
        }
        double[] wp = turnPath.get(turnWpIdx);
        double dx = wp[0]-x, dy = wp[1]-y;
        double dist = Math.sqrt(dx*dx+dy*dy);
        if (dist < TURN_WP_REACH) { turnWpIdx++; return; }

        targetAngle = Math.atan2(dy, dx);
        double diff = angleDiff(targetAngle, angle);
        double maxT = MAX_TURN_RATE * dt;
        angle += Math.abs(diff) <= maxT ? diff : Math.signum(diff)*maxT;

        // Tốc độ giảm trong ngã tư tùy độ cong của turn path
        double turningSpeed = computeTurningSpeed();
        speed = speed < turningSpeed ? Math.min(turningSpeed, speed+accel*dt)
                                     : Math.max(turningSpeed, speed-decel*dt);
        speed = applyFollowDistance(speed, others);

        vx = Math.cos(angle)*speed;
        vy = Math.sin(angle)*speed;
        x += vx*dt; y += vy*dt;
        for (Vehicle o : others) if (o!=this && !o.arrived) resolveVehicleCollision(o);
        if (speed > 1.5) angle = Math.atan2(vy, vx);
        state = State.MOVING;
    }

    /** Tốc độ rẽ: phụ thuộc độ cong của turn path (phân tích từ path hiện tại). */
    private double computeTurningSpeed() {
        if (turnPath == null || turnPath.size() < 3) return maxSpeed * 0.65;
        // Lấy 3 điểm gần nhất để ước tính độ cong
        int i = Math.min(turnWpIdx, turnPath.size()-3);
        double[] a = turnPath.get(i), b = turnPath.get(i+1), c = turnPath.get(i+2);
        double ax=b[0]-a[0], ay=b[1]-a[1];
        double bx=c[0]-b[0], by=c[1]-b[1];
        double cross = Math.abs(ax*by - ay*bx);
        double len   = Math.sqrt(ax*ax+ay*ay) + Math.sqrt(bx*bx+by*by);
        double curvature = len > 0 ? cross / (len*len) : 0;
        // Curvature cao = rẽ gắt = tốc độ thấp hơn
        double speedFraction = Math.max(0.45, 1.0 - curvature * 60);
        return maxSpeed * speedFraction;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  CUBIC BEZIER turn path — tiếp tuyến chính xác tại entry/exit
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Tính đường cong cubic Bezier qua ngã tư.
     *
     * Đảm bảo:
     *   B'(0) song song với hướng vào (entry road direction)
     *   B'(1) song song với hướng ra (exit road direction)
     * → Xe không bao giờ quay vòng, hoạt động đúng với MỌI kích thước đường.
     *
     * Entry: làn trái nếu rẽ trái, làn phải nếu thẳng/rẽ phải.
     * Exit:  làn giữa (s=hw*0.38), lateral steering tự xử lý vị trí cuối.
     */
    private List<double[]> computeTurnPath(Road entry, Road exit, Node inter) {
        double hw = entry.getHalfWidth();

        // Loại rẽ
        double cross = entry.getDirX()*exit.getDirY() - entry.getDirY()*exit.getDirX();
        boolean leftTurn  = cross < -0.28;
        boolean rightTurn = cross >  0.28;

        // ── Entry point (trên stop-line, đúng làn) ────────────────────────
        double entryS    = leftTurn ? hw*0.18 : hw*0.68;
        double entryDist = Math.min(hw * 0.5, entry.getLength() * 0.45);
        double[] P0 = entry.localToWorld(entry.getLength() - entryDist, entryS);

        // ── Exit point (vào giữa đường ra, lateral steering tự điều chỉnh) ─
        double exitDist = Math.min(hw * 0.5, exit.getLength() * 0.45);
        double exitS    = hw * 0.38;   // gần giữa → không gây spin
        double[] P3 = exit.localToWorld(exitDist, exitS);

        // ── Tiếp tuyến entry / exit ────────────────────────────────────────
        // Hướng xe đi vào ngã tư = hướng entry road
        double ex = entry.getDirX(), ey = entry.getDirY();
        // Hướng xe đi ra khỏi ngã tư = hướng exit road
        double fx = exit.getDirX(), fy = exit.getDirY();

        // ── Tension: tỉ lệ với khoảng cách P0→P3, đủ lớn để cong mượt ────
        double ddx = P3[0]-P0[0], ddy = P3[1]-P0[1];
        double dist = Math.sqrt(ddx*ddx + ddy*ddy);
        // Tension nhỏ = cong gắt, tension lớn = cong mềm
        double tension = Math.max(hw*0.4, dist * 0.5);

        // ── Cubic Bezier control points ────────────────────────────────────
        //   B'(0) = 3*(P1-P0) → để song song entry direction: P1 = P0 + entry_dir * T
        //   B'(1) = 3*(P3-P2) → để song song exit direction:  P2 = P3 - exit_dir * T
        double[] P1 = {P0[0] + ex*tension, P0[1] + ey*tension};
        double[] P2 = {P3[0] - fx*tension, P3[1] - fy*tension};

        // ── Sample 16 điểm trên cubic Bezier ──────────────────────────────
        int N = 16;
        List<double[]> pts = new ArrayList<>(N);
        for (int i = 1; i <= N; i++) {
            double t = (double)i / N, mt = 1-t;
            pts.add(new double[]{
                mt*mt*mt*P0[0] + 3*mt*mt*t*P1[0] + 3*mt*t*t*P2[0] + t*t*t*P3[0],
                mt*mt*mt*P0[1] + 3*mt*mt*t*P1[1] + 3*mt*t*t*P2[1] + t*t*t*P3[1]
            });
        }
        return pts;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Đèn giao thông
    // ─────────────────────────────────────────────────────────────────────

    private double reactToLight(IntersectionController ic, Road road, double hw) {
        if (ic == null || road == null || inIntersectionBox) return maxSpeed;
        TrafficLight.LightState ls = ic.getLightState(road);
        if (ls == TrafficLight.LightState.GREEN) return maxSpeed;

        double distToStop = ic.distToStopLine(this, road);
        double frontDist  = distToStop - hitH / 2.0;
        if (frontDist <= 0) return maxSpeed;   // đã vào → tiếp tục

        double brakeDist = hitH * 3.5;
        if (frontDist > brakeDist) return maxSpeed;  // còn xa

        if (ls == TrafficLight.LightState.YELLOW && frontDist > brakeDist * 0.55)
            return maxSpeed;

        double frac = frontDist / brakeDist;
        if (frontDist < hitH * 0.25) { state = State.WAITING_LIGHT; return 0; }
        state = State.SLOWING;
        return maxSpeed * frac * 0.85;
    }

    private boolean isFrontPastStopLine(IntersectionController ic, Road road) {
        if (ic == null || road == null) return false;
        return (ic.distToStopLine(this, road) - hitH/2.0) <= 0;
    }

    private void updateIntersectionBox(RoadNetwork net) {
        for (IntersectionController ic : net.getAllIntersectionControllers()) {
            double dx=x-ic.getNode().getX(), dy=y-ic.getNode().getY();
            if (Math.sqrt(dx*dx+dy*dy) < ic.getHalfWidth() * 1.15) { inIntersectionBox=true; return; }
        }
        inIntersectionBox = false;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Steer + tốc độ thích nghi theo độ dài đường
    // ─────────────────────────────────────────────────────────────────────

    private void steer(Node target, double desiredSpeed, double dt) {
        double dx=target.getX()-x, dy=target.getY()-y;
        double dist=Math.sqrt(dx*dx+dy*dy);
        if (dist > 0.5) aimAt(target);

        double diff=angleDiff(targetAngle,angle), maxT=MAX_TURN_RATE*dt;
        angle += Math.abs(diff)<=maxT ? diff : Math.signum(diff)*maxT;

        // Giảm tốc khi gần node trung gian (không phải đích)
        double want = desiredSpeed;
        if (want > 0 && dist < 50 && pathIndex < path.size()-1)
            want = Math.max(want*0.4, want*dist/50);

        speed = speed < want ? Math.min(want, speed+accel*dt)
                             : Math.max(want, speed-decel*dt);
        vx = Math.cos(angle)*speed;
        vy = Math.sin(angle)*speed;

        if (desiredSpeed == 0 && state != State.WAITING_LIGHT) state = State.STOPPED;
        else if (speed < maxSpeed*0.65) state = State.SLOWING;
        else state = State.MOVING;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Follow distance
    // ─────────────────────────────────────────────────────────────────────

    private double applyFollowDistance(double want, List<Vehicle> others) {
        double ca=Math.cos(angle), sa=Math.sin(angle);
        Vehicle ahead=null; double bd=followDistance*8;
        for (Vehicle o:others){
            if(o==this||o.arrived) continue;
            double ex=o.x-x,ey=o.y-y;
            double along=ex*ca+ey*sa, perp=Math.abs(-ex*sa+ey*ca);
            if(along>0&&along<followDistance*8&&perp<hitW+6&&along<bd){bd=along;ahead=o;}
        }
        if(ahead==null) return want;
        double gap=bd-hitR-ahead.hitR;
        if(gap<=0||gap<followDistance*0.5)      return 0;
        if(gap<followDistance)                  return Math.min(want, ahead.speed*0.7);
        if(gap<followDistance*2)                return Math.min(want, ahead.speed*0.9);
        return want;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Lateral steering (luật tay phải)
    // ─────────────────────────────────────────────────────────────────────

    private void applyLateral(Road road, double dt, List<Vehicle> others) {
        double[] loc=road.worldToLocal(x,y);
        double t=loc[0], s=loc[1], hw=road.getHalfWidth();
        if(t<20||t>road.getLength()-20) return;
        double normalS=hw*0.68, passS=hw*0.18;
        updateOvertake(others,road,t,dt);
        if(preparingLeftTurn)  targetS=hw*0.18;
        else if(overtaking)    targetS=passS;
        else                   targetS=normalS;
        currentS += (targetS-currentS)*Math.min(1.0,dt*3.5);
        double err=currentS-s;
        double latSpd=Math.max(-maxSpeed*0.3,Math.min(maxSpeed*0.3,err*4.5));
        vx+=road.getPerpX()*latSpd; vy+=road.getPerpY()*latSpd;
    }

    private void updateOvertake(List<Vehicle> others, Road road, double myT, double dt) {
        if(overtaking){if((overtakeTimer-=dt)<=0)overtaking=false;return;}
        for(Vehicle o:others){
            if(o==this||o.arrived) continue;
            double[] ol=road.worldToLocal(o.x,o.y);
            if(ol[0]-myT>0&&ol[0]-myT<80&&Math.abs(ol[1]-currentS)<18&&o.speed<speed*0.82){
                overtaking=true;overtakeTimer=3.5;return;
            }
        }
    }

    private void updateLeftTurnPrep(IntersectionController ic, double hw, double dist, RoadNetwork net) {
        if(ic!=null&&pathIndex+1<path.size()&&currentRoad!=null){
            Road exitRoad=net.findRoadBetween(path.get(pathIndex),path.get(pathIndex+1));
            if(exitRoad!=null){
                double cross=currentRoad.getDirX()*exitRoad.getDirY()-currentRoad.getDirY()*exitRoad.getDirX();
                preparingLeftTurn=(cross<-0.28)&&dist<hw*4;
                return;
            }
        }
        preparingLeftTurn=false;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Collision xe–xe
    // ─────────────────────────────────────────────────────────────────────

    public void resolveVehicleCollision(Vehicle o) {
        double dx=x-o.x,dy=y-o.y,dist=Math.sqrt(dx*dx+dy*dy),minD=hitR+o.hitR+SEP;
        if(dist>=minD||dist<1e-6) return;
        double nx=dx/dist,ny=dy/dist,ov=(minD-dist)*PUSH;
        x+=nx*ov;y+=ny*ov;o.x-=nx*ov;o.y-=ny*ov;
        double rvn=(vx-o.vx)*nx+(vy-o.vy)*ny;
        if(rvn<0){vx-=rvn*nx*PUSH;vy-=rvn*ny*PUSH;o.vx+=rvn*nx*PUSH;o.vy+=rvn*ny*PUSH;}
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private void aimAt(Node n){ targetAngle=Math.atan2(n.getY()-y,n.getX()-x); }

    private static double angleDiff(double t,double c){
        double d=t-c; while(d>Math.PI)d-=2*Math.PI; while(d<-Math.PI)d+=2*Math.PI; return d;
    }

    /** Nội suy góc ngắn nhất. */
    private static double lerpAngle(double a, double b, double t) {
        double d = angleDiff(b, a);
        return a + d * t;
    }

    public double distanceTo(Vehicle o){double dx=x-o.x,dy=y-o.y;return Math.sqrt(dx*dx+dy*dy);}

    public double[][] getHitboxCorners(){
        double c=Math.cos(angle),s=Math.sin(angle),hw=hitW/2,hh=hitH/2;
        double[][]lc={{-hw,-hh},{hw,-hh},{hw,hh},{-hw,hh}},wc=new double[4][2];
        for(int i=0;i<4;i++){wc[i][0]=x+lc[i][0]*c-lc[i][1]*s;wc[i][1]=y+lc[i][0]*s+lc[i][1]*c;}
        return wc;
    }

    public void onBarrierHit(Road r,Road.BarrierSide s){state=State.SLOWING;}

    // ── Getters / Setters ──────────────────────────────────────────────────
    public String   getId()               { return id; }
    public String   getType()              { return type; }
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
    public Node     getCurrentTarget()    { return pathIndex<path.size()?path.get(pathIndex):null; }

    public void setX(double v)              { x=v; }
    public void setY(double v)              { y=v; }
    public void setVx(double v)             { vx=v; }
    public void setVy(double v)             { vy=v; }
    public void setState(State s)           { state=s; }
    public void setCurrentRoad(Road r)      { currentRoad=r; }
    public void setFollowDistance(double d) { followDistance=Math.max(1,d); }
    /** Chỉnh gia tốc từ ControlPanel. */
    public void setAcceleration(double a)   { accel=Math.max(10,a); decel=accel*1.6; }
    public double getAcceleration()         { return accel; }
}
