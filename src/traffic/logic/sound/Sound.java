package traffic.logic.sound;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;

public abstract class Sound {

    protected void playSound(String path) {

        new Thread(() -> {

            try {

                File soundFile = new File(path);

                AudioInputStream audioStream =
                        AudioSystem.getAudioInputStream(soundFile);

                Clip clip = AudioSystem.getClip();

                clip.open(audioStream);

                clip.start();

            } catch (UnsupportedAudioFileException e) {
                System.out.println("Unsupported audio file.");
            } catch (IOException e) {
                System.out.println("Cannot find file: " + path);
            } catch (LineUnavailableException e) {
                System.out.println("Audio line unavailable.");
            }

        }).start();
    }
}