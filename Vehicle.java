import java.util.*;

/**
 * Vehicle v8 — Modular intersection turns + perimeter-based arrival.
 *
 * Turn logic separated into 3 methods:
 *   computeStraightPath  — right lane → right lane of road ahead
 *   computeRightTurnPath — right lane → right lane of road to the right
 *   computeLeftTurnPath  — LEFT lane (pre-positioned) → right lane of road to the left
 *
 * ALL use cubic Bezier guaranteeing tangent direction at entry and exit:
 *   P1 = P0 + entryDir * tension   (exit in correct direction)
 *   P2 = P3 - exitDir  * tension   (arrive in correct direction)
 *
 * Arrival: perimeter-based — vehicle does NOT need to reach exact node position,
 *   just enter within TURN_WP_REACH radius. No speed reduction for intermediate nodes.
 */
public class Vehicle {

    public enum State { MOVING, SLOWING, STOPPED, WAITING_LIGHT, YIELDING, ARRIVED }

    // Lane fraction constants (must match IntersectionController)
    private static final double NORMAL_FRAC = IntersectionController.NORMAL_LANE_FRAC; // 0.68
    private static final double PASS_FRAC   = IntersectionController.PASS_LANE_FRAC;   // 0.18

    private final String id;

    // Kinematics
    private double x, y, angle, targetAngle;
    private double vx, vy, speed;
    private final double maxSpeed;
    private double accel, decel;
    private static final double MAX_TURN_RATE = Math.PI * 2.2;

    // Hitbox
    private final double hitW, hitH, hitR;

    // Path
    private final List<Node> path;
    private int    pathIndex;
    private State  state;
    private boolean arrived;

    // Turn path (cubic Bezier waypoints)
    private List<double[]> turnPath  = null;
    private int            turnWpIdx = 0;
    /** Perimeter radius for turn waypoint arrival — larger = doesn't need to hit exact point */
    private static final double TURN_WP_REACH   = 8.0;
    private static final double TURN_START_MULT  = 1.15;

    // Road
    private Road currentRoad;

    // Lateral / right-hand
    private double targetS = 0, currentS = 0;
    private boolean overtaking = false, preparingLeftTurn = false;
    private double  overtakeTimer = 0;

    // Follow distance
    private double followDistance;

    // Intersection
    private boolean inIntersectionBox = false;

    private static final double SEP  = 1.5, PUSH = 0.5;
    private static final double WP_R = Node.ARRIVAL_RADIUS;

    // ─────────────────────────────────────────────────────────────────────

    public Vehicle(String id, double sx, double sy,
                   double hitW, double hitH, double maxSpeed, List<Node> path) {
        if (path==null||path.size()<2) throw new IllegalArgumentException("path ≥ 2 Node");
        this.id=id; this.x=sx; this.y=sy;
        this.hitW=hitW; this.hitH=hitH; this.hitR=Math.max(hitW,hitH)/2.0;
        this.maxSpeed=maxSpeed; this.accel=maxSpeed*2.2; this.decel=maxSpeed*3.5;
        this.path=path; this.pathIndex=1;
        this.state=State.MOVING; this.arrived=false;
        this.followDistance=hitH*(2.0/3.0);
        aimAt(path.get(1)); this.angle=targetAngle;
        this.speed=maxSpeed*0.25;
        this.vx=Math.cos(angle)*speed; this.vy=Math.sin(angle)*speed;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Update
    // ─────────────────────────────────────────────────────────────────────

    public void update(double dt, List<Vehicle> others, RoadNetwork net) {
        if (arrived) return;
        currentRoad=net.findRoadForVehicle(this);
        updateIntersectionBox(net);

        if (turnPath!=null) { followTurnPath(dt,others); return; }

        Node target=path.get(pathIndex);
        double dx=target.getX()-x, dy=target.getY()-y;
        double dist=Math.sqrt(dx*dx+dy*dy);

        // Perimeter-based arrival — no need to reach exact node position
        if (dist < WP_R) {
            if (++pathIndex>=path.size()) {
                state=State.ARRIVED; arrived=true; vx=0; vy=0; return;
            }
            target=path.get(pathIndex);
            dx=target.getX()-x; dy=target.getY()-y; dist=Math.sqrt(dx*dx+dy*dy);
        }

        IntersectionController ic=net.getIntersectionController(target);
        double hw=currentRoad!=null?currentRoad.getHalfWidth():80;

        updateLeftTurnPrep(ic,hw,dist,net);
        double desiredSpeed=reactToLight(ic,currentRoad,hw);

        boolean frontPastStop=isFrontPastStopLine(ic,currentRoad);
        boolean lightOk=frontPastStop||ic==null||ic.getLightState(currentRoad)==TrafficLight.LightState.GREEN;

        if (lightOk&&desiredSpeed>0&&dist<hw*TURN_START_MULT&&pathIndex+1<path.size()) {
            Road exitRoad=net.findRoadBetween(target,path.get(pathIndex+1));
            if (exitRoad!=null&&currentRoad!=null) {
                turnPath=computeTurnPath(currentRoad,exitRoad,target);
                turnWpIdx=0;
                if (turnPath!=null&&!turnPath.isEmpty()) { followTurnPath(dt,others); return; }
            }
        }

        if (ic!=null&&!inIntersectionBox&&desiredSpeed>0) {
            if (ic.shouldYield(this,currentRoad,others)) { desiredSpeed=0; state=State.YIELDING; }
        }

        desiredSpeed=applyFollowDistance(desiredSpeed,others);
        steer(target,desiredSpeed,dt);

        boolean canSteerLaterally=(state!=State.WAITING_LIGHT)&&(state!=State.STOPPED)&&(speed>4)&&!inIntersectionBox;
        if (currentRoad!=null&&canSteerLaterally) applyLateral(currentRoad,dt,others);
        else if (currentRoad!=null&&!canSteerLaterally) {
            double roadAngle=Math.atan2(currentRoad.getDirY(),currentRoad.getDirX());
            angle=lerpAngle(angle,roadAngle,Math.min(1.0,dt*6));
            vx=Math.cos(angle)*speed; vy=Math.sin(angle)*speed;
        }

        x+=vx*dt; y+=vy*dt;
        for (Vehicle o:others) if(o!=this&&!o.arrived) resolveVehicleCollision(o);
        if (currentRoad!=null) currentRoad.resolveBarrierCollision(this,WP_R*1.5);
        if (speed>1.5&&canSteerLaterally) angle=Math.atan2(vy,vx);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Turn path following
    // ─────────────────────────────────────────────────────────────────────

    private void followTurnPath(double dt, List<Vehicle> others) {
        if (turnPath==null||turnWpIdx>=turnPath.size()) {
            turnPath=null;
            if (pathIndex<path.size()) pathIndex++;
            return;
        }
        double[] wp=turnPath.get(turnWpIdx);
        double dx=wp[0]-x, dy=wp[1]-y, dist=Math.sqrt(dx*dx+dy*dy);

        // Perimeter-based: advance to next waypoint when within reach radius
        if (dist<TURN_WP_REACH) { turnWpIdx++; return; }

        targetAngle=Math.atan2(dy,dx);
        double diff=angleDiff(targetAngle,angle), maxT=MAX_TURN_RATE*dt;
        angle+=Math.abs(diff)<=maxT?diff:Math.signum(diff)*maxT;

        double turningSpeed=computeTurningSpeed();
        speed=speed<turningSpeed?Math.min(turningSpeed,speed+accel*dt):Math.max(turningSpeed,speed-decel*dt);
        speed=applyFollowDistance(speed,others);

        vx=Math.cos(angle)*speed; vy=Math.sin(angle)*speed;
        x+=vx*dt; y+=vy*dt;
        for (Vehicle o:others) if(o!=this&&!o.arrived) resolveVehicleCollision(o);
        if (speed>1.5) angle=Math.atan2(vy,vx);
        state=State.MOVING;
    }

    private double computeTurningSpeed() {
        if (turnPath==null||turnPath.size()<3) return maxSpeed*0.65;
        int i=Math.min(turnWpIdx,turnPath.size()-3);
        double[] a=turnPath.get(i),b=turnPath.get(i+1),c=turnPath.get(i+2);
        double ax=b[0]-a[0],ay=b[1]-a[1],bx=c[0]-b[0],by=c[1]-b[1];
        double cross=Math.abs(ax*by-ay*bx), len=Math.sqrt(ax*ax+ay*ay)+Math.sqrt(bx*bx+by*by);
        double curvature=len>0?cross/(len*len):0;
        return maxSpeed*Math.max(0.45, 1.0-curvature*60);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  MODULAR TURN PATH COMPUTATION
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Dispatches to the correct turn method based on cross product of entry/exit directions.
     * cross > 0  → right turn
     * cross < 0  → left turn
     * cross ≈ 0  → straight
     */
    private List<double[]> computeTurnPath(Road entry, Road exit, Node inter) {
        double cross=entry.getDirX()*exit.getDirY()-entry.getDirY()*exit.getDirX();
        if      (cross >  0.28) return computeRightTurnPath(entry, exit);
        else if (cross < -0.28) return computeLeftTurnPath (entry, exit);
        else                    return computeStraightPath  (entry, exit);
    }

    /**
     * STRAIGHT: right lane → right lane of road ahead.
     * Entry and exit both use NORMAL_FRAC (right lane).
     */
    private List<double[]> computeStraightPath(Road entry, Road exit) {
        double hw=entry.getHalfWidth();
        double[] P0=entry.localToWorld(Math.max(0,entry.getLength()-hw*0.5), hw*NORMAL_FRAC);
        double[] P3=exit.localToWorld(Math.min(exit.getLength(),hw*0.5),     hw*NORMAL_FRAC);
        double dx=P3[0]-P0[0],dy=P3[1]-P0[1],d=Math.sqrt(dx*dx+dy*dy);
        double tension=Math.max(hw*0.35,d*0.45);
        return cubicBezier(P0,entry.getDirX(),entry.getDirY(),P3,exit.getDirX(),exit.getDirY(),tension,14);
    }

    /**
     * RIGHT TURN: right lane → right lane of road to the right.
     * Both entry and exit use NORMAL_FRAC (right/outer lane).
     * Smaller tension for a tighter inside corner.
     */
    private List<double[]> computeRightTurnPath(Road entry, Road exit) {
        double hw=entry.getHalfWidth();
        double[] P0=entry.localToWorld(Math.max(0,entry.getLength()-hw*0.5), hw*NORMAL_FRAC);
        double[] P3=exit.localToWorld(Math.min(exit.getLength(),hw*0.5),     hw*NORMAL_FRAC);
        double dx=P3[0]-P0[0],dy=P3[1]-P0[1],d=Math.sqrt(dx*dx+dy*dy);
        double tension=Math.max(hw*0.30,d*0.42);
        return cubicBezier(P0,entry.getDirX(),entry.getDirY(),P3,exit.getDirX(),exit.getDirY(),tension,14);
    }

    /**
     * LEFT TURN: LEFT lane (PASS_FRAC, near centre) → right lane of road to the left.
     * Vehicle pre-positions to left lane before the intersection (handled in applyLateral).
     * Larger tension to arc across the intersection centre.
     */
    private List<double[]> computeLeftTurnPath(Road entry, Road exit) {
        double hw=entry.getHalfWidth();
        // Enter from LEFT lane (near centre line)
        double[] P0=entry.localToWorld(Math.max(0,entry.getLength()-hw*0.5), hw*PASS_FRAC);
        // Exit to RIGHT lane of the left road
        double[] P3=exit.localToWorld(Math.min(exit.getLength(),hw*0.5),     hw*NORMAL_FRAC);
        double dx=P3[0]-P0[0],dy=P3[1]-P0[1],d=Math.sqrt(dx*dx+dy*dy);
        double tension=Math.max(hw*0.50,d*0.55);
        return cubicBezier(P0,entry.getDirX(),entry.getDirY(),P3,exit.getDirX(),exit.getDirY(),tension,16);
    }

    /**
     * Cubic Bezier with guaranteed entry/exit tangent directions.
     *   P1 = P0 + (ex,ey)*tension   → tangent at start = entry direction
     *   P2 = P3 - (fx,fy)*tension   → tangent at end   = exit direction
     * This prevents the spinning/looping issue for any road size.
     */
    private static List<double[]> cubicBezier(double[] P0,double ex,double ey,
                                               double[] P3,double fx,double fy,
                                               double tension,int N) {
        double[] P1={P0[0]+ex*tension, P0[1]+ey*tension};
        double[] P2={P3[0]-fx*tension, P3[1]-fy*tension};
        List<double[]> pts=new ArrayList<>(N);
        for (int i=1;i<=N;i++) {
            double t=(double)i/N, mt=1-t;
            pts.add(new double[]{
                mt*mt*mt*P0[0]+3*mt*mt*t*P1[0]+3*mt*t*t*P2[0]+t*t*t*P3[0],
                mt*mt*mt*P0[1]+3*mt*mt*t*P1[1]+3*mt*t*t*P2[1]+t*t*t*P3[1]
            });
        }
        return pts;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Traffic light / intersection box
    // ─────────────────────────────────────────────────────────────────────

    private double reactToLight(IntersectionController ic, Road road, double hw) {
        if (ic==null||road==null||inIntersectionBox) return maxSpeed;
        TrafficLight.LightState ls=ic.getLightState(road);
        if (ls==TrafficLight.LightState.GREEN) return maxSpeed;
        double distToStop=ic.distToStopLine(this,road);
        double frontDist=distToStop-hitH/2.0;
        if (frontDist<=0) return maxSpeed;
        double brakeDist=hitH*3.5;
        if (frontDist>brakeDist) return maxSpeed;
        if (ls==TrafficLight.LightState.YELLOW&&frontDist>brakeDist*0.55) return maxSpeed;
        if (frontDist<hitH*0.25) { state=State.WAITING_LIGHT; return 0; }
        state=State.SLOWING;
        return maxSpeed*frontDist/brakeDist*0.85;
    }

    private boolean isFrontPastStopLine(IntersectionController ic, Road road) {
        if (ic==null||road==null) return false;
        return (ic.distToStopLine(this,road)-hitH/2.0)<=0;
    }

    private void updateIntersectionBox(RoadNetwork net) {
        for (IntersectionController ic:net.getAllIntersectionControllers()) {
            double dx=x-ic.getNode().getX(),dy=y-ic.getNode().getY();
            if (Math.sqrt(dx*dx+dy*dy)<ic.getHalfWidth()*1.15){inIntersectionBox=true;return;}
        }
        inIntersectionBox=false;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Steer (NO speed reduction for intermediate nodes — only for terminals)
    // ─────────────────────────────────────────────────────────────────────

    private void steer(Node target, double desiredSpeed, double dt) {
        double dx=target.getX()-x,dy=target.getY()-y,dist=Math.sqrt(dx*dx+dy*dy);
        if (dist>0.5) aimAt(target);
        double diff=angleDiff(targetAngle,angle),maxT=MAX_TURN_RATE*dt;
        angle+=Math.abs(diff)<=maxT?diff:Math.signum(diff)*maxT;

        // Only reduce speed near TERMINAL node (last in path), not intermediate
        double want=desiredSpeed;
        boolean nearTerminal=(pathIndex==path.size()-1)&&dist<60;
        if (nearTerminal&&want>0) want=Math.max(want*0.4, want*dist/60);

        speed=speed<want?Math.min(want,speed+accel*dt):Math.max(want,speed-decel*dt);
        vx=Math.cos(angle)*speed; vy=Math.sin(angle)*speed;
        if (desiredSpeed==0&&state!=State.WAITING_LIGHT) state=State.STOPPED;
        else if (speed<maxSpeed*0.65) state=State.SLOWING;
        else state=State.MOVING;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Follow distance
    // ─────────────────────────────────────────────────────────────────────

    private double applyFollowDistance(double want, List<Vehicle> others) {
        double ca=Math.cos(angle),sa=Math.sin(angle);
        Vehicle ahead=null; double bd=followDistance*8;
        for (Vehicle o:others){
            if(o==this||o.arrived) continue;
            double ex=o.x-x,ey=o.y-y;
            double along=ex*ca+ey*sa,perp=Math.abs(-ex*sa+ey*ca);
            if(along>0&&along<followDistance*8&&perp<hitW+6&&along<bd){bd=along;ahead=o;}
        }
        if(ahead==null) return want;
        double gap=bd-hitR-ahead.hitR;
        if(gap<=0||gap<followDistance*0.5) return 0;
        if(gap<followDistance) return Math.min(want,ahead.speed*0.7);
        if(gap<followDistance*2) return Math.min(want,ahead.speed*0.9);
        return want;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Lateral (right-hand traffic)
    // ─────────────────────────────────────────────────────────────────────

    private void applyLateral(Road road, double dt, List<Vehicle> others) {
        double[] loc=road.worldToLocal(x,y);
        double t=loc[0],s=loc[1],hw=road.getHalfWidth();
        if(t<20||t>road.getLength()-20) return;
        updateOvertake(others,road,t,dt);
        if(preparingLeftTurn)  targetS=hw*PASS_FRAC;
        else if(overtaking)    targetS=hw*PASS_FRAC;
        else                   targetS=hw*NORMAL_FRAC;
        currentS+=(targetS-currentS)*Math.min(1.0,dt*3.5);
        double err=currentS-s;
        double latSpd=Math.max(-maxSpeed*0.3,Math.min(maxSpeed*0.3,err*4.5));
        vx+=road.getPerpX()*latSpd; vy+=road.getPerpY()*latSpd;
    }

    private void updateOvertake(List<Vehicle> others,Road road,double myT,double dt){
        if(overtaking){if((overtakeTimer-=dt)<=0)overtaking=false;return;}
        for(Vehicle o:others){
            if(o==this||o.arrived)continue;
            double[]ol=road.worldToLocal(o.x,o.y);
            if(ol[0]-myT>0&&ol[0]-myT<80&&Math.abs(ol[1]-currentS)<18&&o.speed<speed*0.82){overtaking=true;overtakeTimer=3.5;return;}
        }
    }

    private void updateLeftTurnPrep(IntersectionController ic,double hw,double dist,RoadNetwork net){
        if(ic!=null&&pathIndex+1<path.size()&&currentRoad!=null){
            Road exitRoad=net.findRoadBetween(path.get(pathIndex),path.get(pathIndex+1));
            if(exitRoad!=null){
                double cross=currentRoad.getDirX()*exitRoad.getDirY()-currentRoad.getDirY()*exitRoad.getDirX();
                preparingLeftTurn=(cross<-0.28)&&dist<hw*4; return;
            }
        }
        preparingLeftTurn=false;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Collision
    // ─────────────────────────────────────────────────────────────────────

    public void resolveVehicleCollision(Vehicle o){
        double dx=x-o.x,dy=y-o.y,dist=Math.sqrt(dx*dx+dy*dy),minD=hitR+o.hitR+SEP;
        if(dist>=minD||dist<1e-6)return;
        double nx=dx/dist,ny=dy/dist,ov=(minD-dist)*PUSH;
        x+=nx*ov;y+=ny*ov;o.x-=nx*ov;o.y-=ny*ov;
        double rvn=(vx-o.vx)*nx+(vy-o.vy)*ny;
        if(rvn<0){vx-=rvn*nx*PUSH;vy-=rvn*ny*PUSH;o.vx+=rvn*nx*PUSH;o.vy+=rvn*ny*PUSH;}
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private void aimAt(Node n){targetAngle=Math.atan2(n.getY()-y,n.getX()-x);}
    private static double angleDiff(double t,double c){double d=t-c;while(d>Math.PI)d-=2*Math.PI;while(d<-Math.PI)d+=2*Math.PI;return d;}
    private static double lerpAngle(double a,double b,double t){return a+angleDiff(b,a)*t;}
    public double distanceTo(Vehicle o){double dx=x-o.x,dy=y-o.y;return Math.sqrt(dx*dx+dy*dy);}
    public double[][]getHitboxCorners(){double c=Math.cos(angle),s=Math.sin(angle),hw=hitW/2,hh=hitH/2;double[][]lc={{-hw,-hh},{hw,-hh},{hw,hh},{-hw,hh}},wc=new double[4][2];for(int i=0;i<4;i++){wc[i][0]=x+lc[i][0]*c-lc[i][1]*s;wc[i][1]=y+lc[i][0]*s+lc[i][1]*c;}return wc;}
    public void onBarrierHit(Road r,Road.BarrierSide s){state=State.SLOWING;}

    // Getters/Setters
    public String  getId()               {return id;}
    public double  getX()                {return x;}
    public double  getY()                {return y;}
    public double  getAngle()            {return angle;}
    public double  getSpeed()            {return speed;}
    public double  getVx()               {return vx;}
    public double  getVy()               {return vy;}
    public double  getHitboxWidth()      {return hitW;}
    public double  getHitboxHeight()     {return hitH;}
    public double  getHitboxRadius()     {return hitR;}
    public double  getMaxSpeed()         {return maxSpeed;}
    public State   getState()            {return state;}
    public boolean isArrived()           {return arrived;}
    public List<Node> getPath()          {return path;}
    public int     getPathIndex()        {return pathIndex;}
    public Road    getCurrentRoad()      {return currentRoad;}
    public boolean isOvertaking()        {return overtaking;}
    public boolean isInIntersectionBox() {return inIntersectionBox;}
    public double  getFollowDistance()   {return followDistance;}
    public Node    getDestination()      {return path.get(path.size()-1);}
    public Node    getOrigin()           {return path.get(0);}
    public Node    getCurrentTarget()    {return pathIndex<path.size()?path.get(pathIndex):null;}
    public void setX(double v)           {x=v;}
    public void setY(double v)           {y=v;}
    public void setVx(double v)          {vx=v;}
    public void setVy(double v)          {vy=v;}
    public void setState(State s)        {state=s;}
    public void setCurrentRoad(Road r)   {currentRoad=r;}
    public void setFollowDistance(double d){followDistance=Math.max(1,d);}
    public void setAcceleration(double a){accel=Math.max(10,a);decel=accel*1.6;}
    public double getAcceleration()      {return accel;}
}
