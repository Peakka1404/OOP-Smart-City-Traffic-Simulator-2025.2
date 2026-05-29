import java.util.*;

/**
 * IntersectionController v8 — Flexible intersection supporting any road direction.
 *
 * NEW: getLaneNodes() returns 16 lane-center positions (green dots from diagrams):
 *   For each approach road × 2 approach lanes + each departure road × 2 departure lanes.
 *
 * Traffic light position: RIGHT side of road direction, at intersection boundary.
 * Adjustable via TL_SIDE_OFFSET (px outside road barrier). Default = 18.
 *   *** TL_SIDE_OFFSET is the parameter to change traffic light distance from road edge ***
 *
 * Phase grouping: auto-detects opposing road pairs, no hardcoded N/S/E/W assumption.
 * Works for cardinal AND diagonal roads (45°/135°).
 *
 * Intersection type: regular (≥4 roads = traffic lights) or pass-through (< 4 = no lights).
 */
public class IntersectionController {

    // ── ADJUSTABLE PARAMETER ────────────────────────────────────────────
    /**
     * Distance (px) from road barrier to traffic light housing.
     * Increase for lights further from road, decrease to move closer.
     * Typical range: 8 to 30.
     * *** This is the parameter the user asked to be notified about ***
     */
    public static double TL_SIDE_OFFSET = 18.0;

    // ── Lane fraction constants (match Vehicle.java) ──────────────────────
    public static final double NORMAL_LANE_FRAC = 0.68;  // right/slow lane
    public static final double PASS_LANE_FRAC   = 0.18;  // left/fast lane

    // ── Core state ───────────────────────────────────────────────────────
    private final Node   node;
    private final double halfWidth;

    private final Map<Road, TrafficLight> lights     = new LinkedHashMap<>();
    private final List<Road>             groupA      = new ArrayList<>();
    private final List<Road>             groupB      = new ArrayList<>();
    private final boolean                hasLights;  // false for < 4 approach roads

    private enum Phase { A_GREEN, A_YELLOW, B_GREEN, B_YELLOW }
    private Phase  phase = Phase.A_GREEN;
    private double phaseTimer;

    private double greenTime  = 8.0;
    private double yellowTime = 2.5;

    // ─────────────────────────────────────────────────────────────────────

    public IntersectionController(Node node, double halfWidth) {
        this.node      = node;
        this.halfWidth = halfWidth;
        this.phaseTimer = greenTime;
        this.hasLights  = false; // set after registerApproachRoad + initPhases
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Registration
    // ─────────────────────────────────────────────────────────────────────

    public void registerApproachRoad(Road road) {
        if (lights.containsKey(road)) return;

        // Traffic light: RIGHT side of road direction, outside barrier
        // s = + (halfWidth + TL_SIDE_OFFSET) → right side, outside
        double stopT = Math.max(0, road.getLength() - halfWidth);
        double[] pos = road.localToWorld(stopT, road.getHalfWidth() + TL_SIDE_OFFSET);
        TrafficLight tl = new TrafficLight(road.getId(), pos[0], pos[1]);
        lights.put(road, tl);
    }

    /**
     * Assigns approach roads to two alternating phases.
     * Uses angle-based pairing: find each road's best opposing partner (≈180° apart).
     * Works for any road direction, including 45°/135° diagonals.
     */
    public void initPhases() {
        groupA.clear(); groupB.clear();

        List<Road> remaining = new ArrayList<>(lights.keySet());
        if (remaining.isEmpty()) return;

        // Pair each road with its most-opposing partner (closest to 180° apart)
        List<Road> paired = new ArrayList<>();
        for (Road r : remaining) {
            if (paired.contains(r)) continue;
            double ax = r.getDirX(), ay = r.getDirY();
            Road bestOpponent = null;
            double bestDot = Double.MAX_VALUE;
            for (Road s : remaining) {
                if (s == r || paired.contains(s)) continue;
                double dot = ax*s.getDirX() + ay*s.getDirY();
                // Most-opposing = most negative dot (approaching -1)
                if (dot < bestDot) { bestDot = dot; bestOpponent = s; }
            }
            if (bestOpponent != null && bestDot < -0.3) {
                groupA.add(r); groupA.add(bestOpponent);
                paired.add(r); paired.add(bestOpponent);
            }
        }
        // Unpaired roads go to groupB (perpendicular)
        for (Road r : remaining) {
            if (!grouped(r)) groupB.add(r);
        }
        // If groupB is empty (all paired into A), split A evenly
        if (groupB.isEmpty() && groupA.size() >= 2) {
            int half = groupA.size() / 2;
            groupB.addAll(groupA.subList(half, groupA.size()));
            groupA.subList(half, groupA.size()).clear();
        }

        // Apply timing
        for (TrafficLight tl : lights.values()) {
            tl.setGreenTime(greenTime); tl.setYellowTime(yellowTime);
            tl.setRedTime(greenTime + yellowTime);
        }

        // Check if intersection has enough roads for traffic lights
        // (hasLights set separately; for < 4 roads just default green)
        if (lights.size() < 4) {
            for (TrafficLight tl : lights.values()) tl.forceGreen();
            return;
        }

        for (Road r : groupA) lights.get(r).forceGreen();
        for (Road r : groupB) { TrafficLight tl = lights.get(r); if (tl!=null) tl.forceRed(); }
        phaseTimer = greenTime;
        phase = Phase.A_GREEN;
    }

    private boolean grouped(Road r) { return groupA.contains(r) || groupB.contains(r); }

    // ─────────────────────────────────────────────────────────────────────
    //  16 Lane-Center Nodes (the green dots in the diagrams)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the 16 lane-center positions at the intersection boundary.
     *
     * Layout (per road arm, at the stop-line edge):
     *   Approach (coming in):
     *     - Right lane center: localToWorld(length - icHW, hw * NORMAL_LANE_FRAC)
     *     - Left  lane center: localToWorld(length - icHW, hw * PASS_LANE_FRAC)
     *   Departure (going out, for the opposing direction road):
     *     - Right lane center: localToWorld(icHW, hw * NORMAL_LANE_FRAC)
     *     - Left  lane center: localToWorld(icHW, hw * PASS_LANE_FRAC)
     *
     * For a 4-way intersection: 4 arms × 4 nodes = 16 total.
     * Each double[] is {x, y, isApproach (1=approach, 0=departure)}.
     */
    public List<double[]> getLaneNodes() {
        List<double[]> result = new ArrayList<>();
        double icHW = halfWidth;

        // Approach nodes (roads coming INTO intersection, registered in lights map)
        for (Road r : lights.keySet()) {
            double hw = r.getHalfWidth();
            double t  = Math.max(0, r.getLength() - icHW);
            double[] rn = r.localToWorld(t, hw * NORMAL_LANE_FRAC); // right lane
            double[] ln = r.localToWorld(t, hw * PASS_LANE_FRAC);   // left lane
            result.add(new double[]{rn[0], rn[1], 1});
            result.add(new double[]{ln[0], ln[1], 1});
        }

        // Departure nodes (roads going OUT from intersection)
        for (Road r : node.getOutgoingRoads()) {
            double hw = r.getHalfWidth();
            double t  = Math.min(r.getLength(), icHW);
            double[] rn = r.localToWorld(t, hw * NORMAL_LANE_FRAC); // right lane
            double[] ln = r.localToWorld(t, hw * PASS_LANE_FRAC);   // left lane
            result.add(new double[]{rn[0], rn[1], 0});
            result.add(new double[]{ln[0], ln[1], 0});
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Update
    // ─────────────────────────────────────────────────────────────────────

    public void update(double dt) {
        for (TrafficLight tl : lights.values()) tl.update(dt);
        if (lights.size() < 4) return;  // no phase cycling for small intersections

        phaseTimer -= dt;
        if (phaseTimer > 0) return;

        switch (phase) {
            case A_GREEN  -> { for (Road r:groupA){TrafficLight t=lights.get(r);if(t!=null)t.forceYellow();} phaseTimer=yellowTime; phase=Phase.A_YELLOW; }
            case A_YELLOW -> { for (Road r:groupA){TrafficLight t=lights.get(r);if(t!=null)t.forceRed();} for(Road r:groupB){TrafficLight t=lights.get(r);if(t!=null)t.forceGreen();} phaseTimer=greenTime; phase=Phase.B_GREEN; }
            case B_GREEN  -> { for (Road r:groupB){TrafficLight t=lights.get(r);if(t!=null)t.forceYellow();} phaseTimer=yellowTime; phase=Phase.B_YELLOW; }
            case B_YELLOW -> { for (Road r:groupB){TrafficLight t=lights.get(r);if(t!=null)t.forceRed();} for(Road r:groupA){TrafficLight t=lights.get(r);if(t!=null)t.forceGreen();} phaseTimer=greenTime; phase=Phase.A_GREEN; }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Queries
    // ─────────────────────────────────────────────────────────────────────

    public TrafficLight.LightState getLightState(Road road) {
        TrafficLight tl = lights.get(road);
        return tl == null ? TrafficLight.LightState.GREEN : tl.getState();
    }

    public TrafficLight getLight(Road road) { return lights.get(road); }

    public double[] stopLinePos(Road road) {
        double t = Math.max(0, road.getLength() - halfWidth);
        return road.localToWorld(t, 0);
    }

    public double distToStopLine(Vehicle v, Road road) {
        double[] loc = road.worldToLocal(v.getX(), v.getY());
        return (road.getLength() - halfWidth) - loc[0];
    }

    public boolean shouldYield(Vehicle me, Road myRoad, List<Vehicle> others) {
        double cx=node.getX(), cy=node.getY(), boxR=halfWidth*1.3;
        for (Vehicle other : others) {
            if (other==me||other.isArrived()) continue;
            double odx=other.getX()-cx, ody=other.getY()-cy;
            double odist=Math.sqrt(odx*odx+ody*ody);
            if (odist<boxR && pathsConflict(me,other)) return true;
            double mdist=Math.sqrt((me.getX()-cx)*(me.getX()-cx)+(me.getY()-cy)*(me.getY()-cy));
            if (mdist>boxR*2||odist>boxR*2) continue;
            if (!pathsConflict(me,other)) continue;
            double mcos=Math.cos(me.getAngle()), msin=Math.sin(me.getAngle());
            if (mcos*ody - msin*odx > 0) return true;
        }
        return false;
    }

    private boolean pathsConflict(Vehicle a, Vehicle b) {
        double dot=Math.cos(a.getAngle())*Math.cos(b.getAngle())+Math.sin(a.getAngle())*Math.sin(b.getAngle());
        return dot < 0.5;
    }

    // Timing
    public void setGreenTime(double t)  { greenTime=Math.max(2,t); for(TrafficLight tl:lights.values()) tl.setGreenTime(greenTime); }
    public void setYellowTime(double t) { yellowTime=Math.max(0.5,t); for(TrafficLight tl:lights.values()) tl.setYellowTime(yellowTime); }
    public double getGreenTime()        { return greenTime; }
    public double getYellowTime()       { return yellowTime; }

    public Node                     getNode()      { return node; }
    public double                   getHalfWidth() { return halfWidth; }
    public Map<Road,TrafficLight>   getLights()    { return Collections.unmodifiableMap(lights); }
    public Collection<TrafficLight> getAllLights()  { return lights.values(); }
    public int getRoadCount()                      { return lights.size(); }
}
