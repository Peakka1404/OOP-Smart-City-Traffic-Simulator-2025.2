package traffic.logic.sound;

public class EngineSound extends Sound {

    public void carEngine() {
        playSound("sounds/car_engine.wav");
    }

    public void motorbikeEngine() {
        playSound("sounds/motorbike_engine.wav");
    }

    public void ambulanceSiren() {
        playSound("sounds/ambulance.wav");
    }

    public void fireTruckSiren() {
        playSound("sounds/firetruck.wav");
    }

    public void policeSiren() {
        playSound("sounds/police.wav");
    }
}