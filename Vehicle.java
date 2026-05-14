import java.util.*;

/**
 * Vehicle — Phương tiện với đèn giao thông đúng nghĩa:
 *  • Xe chạy TỰ DO trên đường, chỉ dừng khi đầu xe chạm VÀO VẠCH BIÊN ngã tư (đèn đỏ).
 *  • Không dừng giữa đường vì đèn đỏ — chỉ dừng ngay tại ranh giới hộp ngã tư.
 *  • Rẽ Bezier mượt qua ngã tư khi đèn xanh.
 *  • Giữ followDistance với xe phía trước.
 *  • Luật tay phải, chuyển làn trái trước khi rẽ trái.
 */
public class Vehicle {

    public enum State { MOVING, SLOWING, STOPPED, WAITING_LIGHT, YIELDING, ARRIVED }

    private final String id;

    // ── Kinematics ────────────────────────────────────────────────────────
    private double x, y, angle, targetAngle;
    private double vx, vy, speed;
    private final double maxSpeed, accel, decel;
    private static final double MAX_TURN_RATE = Math.PI * 1.8;

    // ── Hitbox ────────────────────────────────────────────────────────────
    private final double hitW, hitH, hitR;

    // ── Path ──────────────────────────────────────────────────────────────
    private final List<Node> path;
    private int    pathIndex;
    private State  state;
    private boolean arrived;

    // ── Turn path (Bezier qua ngã tư) ─────────────────────────────────────
    private List<double[]> turnPath  = null;
    private int            turnWpIdx = 0;
    private static final double TURN_WP_REACH    = 5.0;
    // Bắt đầu tính turn khi dist < hw * TURN_MULT
    private static final double TURN_START_MULT  = 1.15;

    // ── Road ─────────────────────────────────────────────────────────────
    private Road currentRoad;

    // ── Lateral / right-hand ──────────────────────────────────────────────
    private double targetS = 0, currentS = 0;
    private boolean overtaking = false, preparingLeftTurn = false;
    private double  overtakeTimer = 0;

    // ── Follow distance ───────────────────────────────────────────────────
    /** Khoảng cách tối thiểu tâm-tâm với xe trước (px). Default = 2/3 chiều dài xe. */
    private double followDistance;

    // ── Intersection ──────────────────────────────────────────────────────
    private boolean inIntersectionBox = false;

    // ── Collision ─────────────────────────────────────────────────────────
    private static final double SEP  = 1.5, PUSH = 0.5;
    private static final double WP_R = Node.ARRIVAL_RADIUS;

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
        this.followDistance = hitH * (2.0/3.0);
        aimAt(path.get(1));
        this.angle = targetAngle;
        this.speed = maxSpeed * 0.3;
        this.vx = Math.cos(angle)*speed; this.vy = Math.sin(angle)*speed;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Update
    // ─────────────────────────────────────────────────────────────────────

    public void update(double dt, List<Vehicle> others, RoadNetwork net) {
        if (arrived) return;
        currentRoad = net.findRoadForVehicle(this);
        updateIntersectionBox(net);

        // ── Đang theo turn path (Bezier qua ngã tư) ───────────────────────
        if (turnPath != null) {
            followTurnPath(dt, others, net);
            return;
        }

        // ── Di chuyển bình thường ─────────────────────────────────────────
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

        // Chuẩn bị rẽ trái → sang làn trái
        updateLeftTurnPrep(ic, hw, dist, net);

        // ── Phản ứng đèn: xe chỉ dừng tại biên ngã tư ───────────────────
        double desiredSpeed = reactToLight(ic, currentRoad, hw);

        // ── Kích hoạt turn path khi đến biên ngã tư VÀ đèn xanh ──────────
        boolean frontPastStop = isFrontPastStopLine(ic, currentRoad, hw);
        boolean lightOk = frontPastStop
                || ic == null
                || ic.getLightState(currentRoad) == TrafficLight.LightState.GREEN;

        if (lightOk && desiredSpeed > 0 && dist < hw * TURN_START_MULT
                && pathIndex + 1 < path.size()) {
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

        // Yield
        if (ic != null && !inIntersectionBox && desiredSpeed > 0) {
            if (ic.shouldYield(this, currentRoad, others)) {
                desiredSpeed = 0; state = State.YIELDING;
            }
        }

        desiredSpeed = applyFollowDistance(desiredSpeed, others);
        steer(target, desiredSpeed, dt);
        if (currentRoad != null) applyLateral(currentRoad, dt, others);

        x += vx*dt; y += vy*dt;
        for (Vehicle o : others) if (o!=this && !o.arrived) resolveVehicleCollision(o);
        if (currentRoad != null) currentRoad.resolveBarrierCollision(this, WP_R*1.5);
        if (speed > 0.5) angle = Math.atan2(vy, vx);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Phản ứng đèn — CHỈ dừng tại biên ngã tư
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Xe chạy TỰ DO trên đường.
     * Chỉ phanh lại khi đầu xe sắp CHẠM vào vạch biên hộp ngã tư (stop line).
     *
     * distToStop = khoảng cách từ TÂM xe đến stop-line.
     * frontDist  = khoảng cách từ ĐẦU xe đến stop-line = distToStop - hitH/2.
     *
     * Phanh chỉ khi frontDist ∈ (0, brakeDist].
     * Nếu đầu xe đã qua stop-line (frontDist ≤ 0): committed → tiếp tục không dừng.
     */
    private double reactToLight(IntersectionController ic, Road road, double hw) {
        if (ic == null || road == null || inIntersectionBox) return maxSpeed;

        TrafficLight.LightState ls = ic.getLightState(road);
        if (ls == TrafficLight.LightState.GREEN) return maxSpeed;

        // RED hoặc YELLOW
        double distToStop = ic.distToStopLine(this, road);
        double frontDist  = distToStop - hitH / 2.0;

        // Đầu xe đã qua vạch → đã vào, không thể dừng ngược
        if (frontDist <= 0) return maxSpeed;

        // Vùng phanh: chỉ trong khoảng hitH*3 trước vạch
        double brakeDist = hitH * 3.5;

        if (frontDist > brakeDist) return maxSpeed;   // còn xa → chạy bình thường

        // YELLOW & còn xa: tiếp tục
        if (ls == TrafficLight.LightState.YELLOW && frontDist > brakeDist * 0.55)
            return maxSpeed;

        // Phanh tỉ lệ thuận với khoảng cách còn lại
        double frac = frontDist / brakeDist;
        if (frontDist < hitH * 0.25) {
            state = State.WAITING_LIGHT;
            return 0;
        }
        state = State.SLOWING;
        return maxSpeed * frac * 0.85;
    }

    /** Đầu xe đã vượt qua stop-line chưa? */
    private boolean isFrontPastStopLine(IntersectionController ic, Road road, double hw) {
        if (ic == null || road == null) return false;
        double distToStop = ic.distToStopLine(this, road);
        return (distToStop - hitH / 2.0) <= 0;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Turn path Bezier
    // ─────────────────────────────────────────────────────────────────────

    private void followTurnPath(double dt, List<Vehicle> others, RoadNetwork net) {
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

        double turningSpeed = maxSpeed * 0.70;
        speed = speed < turningSpeed ? Math.min(turningSpeed, speed+accel*dt)
                                     : Math.max(turningSpeed, speed-decel*dt);
        speed = applyFollowDistance(speed, others);
        vx = Math.cos(angle)*speed; vy = Math.sin(angle)*speed;
        x += vx*dt; y += vy*dt;
        for (Vehicle o : others) if (o!=this && !o.arrived) resolveVehicleCollision(o);
        if (speed > 0.5) angle = Math.atan2(vy, vx);
        state = State.MOVING;
    }

    private List<double[]> computeTurnPath(Road entry, Road exit, Node node) {
        double hw = entry.getHalfWidth();
        double cx = node.getX(), cy = node.getY();
        double cross = entry.getDirX()*exit.getDirY() - entry.getDirY()*exit.getDirX();
        boolean right = cross >  0.3, left = cross < -0.3;

        double entryS = left ? hw*0.18 : hw*0.68;
        double entryT = Math.max(0, entry.getLength() - hw*0.55);
        double[] P0 = entry.localToWorld(entryT, entryS);

        double exitS = hw * 0.68;
        double exitT = hw * 0.55;
        double[] P2 = exit.localToWorld(exitT, exitS);

        double[] P1;
        if (!right && !left) {
            P1 = new double[]{(P0[0]+P2[0])/2, (P0[1]+P2[1])/2};
        } else if (right) {
            double px=entry.getPerpX(), py=entry.getPerpY();
            P1 = new double[]{cx + px*hw*0.7, cy + py*hw*0.7};
        } else {
            double fx=exit.getDirX(), fy=exit.getDirY();
            P1 = new double[]{cx + fx*hw*0.4 - entry.getDirX()*hw*0.2,
                              cy + fy*hw*0.4 - entry.getDirY()*hw*0.2};
        }

        int N = 12;
        List<double[]> pts = new ArrayList<>();
        for (int i = 1; i <= N; i++) {
            double t = (double)i/N, mt = 1-t;
            pts.add(new double[]{
                mt*mt*P0[0] + 2*mt*t*P1[0] + t*t*P2[0],
                mt*mt*P0[1] + 2*mt*t*P1[1] + t*t*P2[1]
            });
        }
        return pts;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private void updateLeftTurnPrep(IntersectionController ic, double hw, double dist, RoadNetwork net) {
        if (ic != null && pathIndex + 1 < path.size() && currentRoad != null) {
            Road exitRoad = net.findRoadBetween(path.get(pathIndex), path.get(pathIndex+1));
            if (exitRoad != null) {
                double cross = currentRoad.getDirX()*exitRoad.getDirY()
                             - currentRoad.getDirY()*exitRoad.getDirX();
                preparingLeftTurn = (cross < -0.3) && dist < hw * 4;
                return;
            }
        }
        preparingLeftTurn = false;
    }

    private void updateIntersectionBox(RoadNetwork net) {
        for (IntersectionController ic : net.getAllIntersectionControllers()) {
            double dx=x-ic.getNode().getX(), dy=y-ic.getNode().getY();
            if (Math.sqrt(dx*dx+dy*dy) < ic.getHalfWidth() * 1.15) { inIntersectionBox=true; return; }
        }
        inIntersectionBox = false;
    }

    private void steer(Node target, double desiredSpeed, double dt) {
        double dx=target.getX()-x, dy=target.getY()-y;
        double dist=Math.sqrt(dx*dx+dy*dy);
        if (dist > 0.5) aimAt(target);
        double diff=angleDiff(targetAngle,angle), maxT=MAX_TURN_RATE*dt;
        angle += Math.abs(diff)<=maxT ? diff : Math.signum(diff)*maxT;
        double want=desiredSpeed;
        if (want>0 && dist<40 && pathIndex<path.size()-1) want=Math.max(want*0.45,want*dist/40);
        speed = speed<want ? Math.min(want,speed+accel*dt) : Math.max(want,speed-decel*dt);
        vx=Math.cos(angle)*speed; vy=Math.sin(angle)*speed;
        if (desiredSpeed==0) state=State.STOPPED;
        else if (speed<maxSpeed*0.7) state=State.SLOWING;
        else state=State.MOVING;
    }

    private double applyFollowDistance(double want, List<Vehicle> others) {
        double ca=Math.cos(angle), sa=Math.sin(angle);
        Vehicle ahead=null; double bd=followDistance*8;
        for (Vehicle o:others){
            if(o==this||o.arrived) continue;
            double ex=o.x-x,ey=o.y-y;
            double along=ex*ca+ey*sa, perp=Math.abs(-ex*sa+ey*ca);
            if(along>0&&along<followDistance*8&&perp<hitW+6&&along<bd){bd=along;ahead=o;}
        }
        if (ahead==null) return want;
        double gap=bd-hitR-ahead.hitR;
        if(gap<=0||gap<followDistance*0.5)           return 0;
        if(gap<followDistance)                        return Math.min(want, ahead.speed*0.7);
        if(gap<followDistance*2)                      return Math.min(want, ahead.speed*0.9);
        return want;
    }

    private void applyLateral(Road road, double dt, List<Vehicle> others) {
        double[] loc=road.worldToLocal(x,y);
        double t=loc[0],s=loc[1],hw=road.getHalfWidth();
        if(t<25||t>road.getLength()-25) return;
        double normalS=hw*0.68, passS=hw*0.18;
        updateOvertake(others,road,t,dt);
        if (preparingLeftTurn) targetS=hw*0.18;
        else if (overtaking)   targetS=passS;
        else                   targetS=normalS;
        currentS += (targetS-currentS)*Math.min(1.0,dt*4.0);
        double err=currentS-s;
        double latSpd=Math.max(-maxSpeed*0.35,Math.min(maxSpeed*0.35,err*5.0));
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

    public void resolveVehicleCollision(Vehicle o) {
        double dx=x-o.x,dy=y-o.y,dist=Math.sqrt(dx*dx+dy*dy),minD=hitR+o.hitR+SEP;
        if(dist>=minD||dist<1e-6) return;
        double nx=dx/dist,ny=dy/dist,ov=(minD-dist)*PUSH;
        x+=nx*ov;y+=ny*ov;o.x-=nx*ov;o.y-=ny*ov;
        double rvn=(vx-o.vx)*nx+(vy-o.vy)*ny;
        if(rvn<0){vx-=rvn*nx*PUSH;vy-=rvn*ny*PUSH;o.vx+=rvn*nx*PUSH;o.vy+=rvn*ny*PUSH;}
    }

    private void aimAt(Node n){targetAngle=Math.atan2(n.getY()-y,n.getX()-x);}
    private static double angleDiff(double t,double c){
        double d=t-c; while(d>Math.PI)d-=2*Math.PI; while(d<-Math.PI)d+=2*Math.PI; return d;
    }
    public double distanceTo(Vehicle o){double dx=x-o.x,dy=y-o.y;return Math.sqrt(dx*dx+dy*dy);}

    public double[][] getHitboxCorners(){
        double c=Math.cos(angle),s=Math.sin(angle),hw=hitW/2,hh=hitH/2;
        double[][]lc={{-hw,-hh},{hw,-hh},{hw,hh},{-hw,hh}},wc=new double[4][2];
        for(int i=0;i<4;i++){wc[i][0]=x+lc[i][0]*c-lc[i][1]*s;wc[i][1]=y+lc[i][0]*s+lc[i][1]*c;}
        return wc;
    }
    public void onBarrierHit(Road r,Road.BarrierSide s){state=State.SLOWING;}

    // Getters / Setters
    public String   getId()              { return id; }
    public double   getX()               { return x; }
    public double   getY()               { return y; }
    public double   getAngle()           { return angle; }
    public double   getSpeed()           { return speed; }
    public double   getVx()              { return vx; }
    public double   getVy()              { return vy; }
    public double   getHitboxWidth()     { return hitW; }
    public double   getHitboxHeight()    { return hitH; }
    public double   getHitboxRadius()    { return hitR; }
    public double   getMaxSpeed()        { return maxSpeed; }
    public State    getState()           { return state; }
    public boolean  isArrived()          { return arrived; }
    public List<Node> getPath()          { return path; }
    public int      getPathIndex()       { return pathIndex; }
    public Road     getCurrentRoad()     { return currentRoad; }
    public boolean  isOvertaking()       { return overtaking; }
    public boolean  isInIntersectionBox(){ return inIntersectionBox; }
    public double   getFollowDistance()  { return followDistance; }
    public Node     getDestination()     { return path.get(path.size()-1); }
    public Node     getOrigin()          { return path.get(0); }
    public Node     getCurrentTarget()   { return pathIndex<path.size()?path.get(pathIndex):null; }
    public void setX(double v)           { x=v; }
    public void setY(double v)           { y=v; }
    public void setVx(double v)          { vx=v; }
    public void setVy(double v)          { vy=v; }
    public void setState(State s)        { state=s; }
    public void setCurrentRoad(Road r)   { currentRoad=r; }
    public void setFollowDistance(double d){ followDistance=Math.max(1,d); }
}
