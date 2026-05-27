package view;

import java.awt.Graphics2D;
import logic.Vehicle;
import logic.Road;
import logic.TrafficLight;

public interface ViewRenderer {
    void drawVehicle(Graphics2D g2d, Vehicle v);
    void drawRoad(Graphics2D g2d, Road r);
    void drawTrafficLight(Graphics2D g2d, TrafficLight tl);
    
    void renderAll(Graphics2D g2d); 
}