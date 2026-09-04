import com.github.pfichtner.espressomachine.api.*;

class PwmFade {
    static void main() {
        int duty = 0;
        int step = 1;

        while (true) {
            GPIO.analogWrite(9, duty);
            Delay.delay(10);
            duty += step;
            if (duty >= 255) {
                duty = 255;
                step = -1;
            } else if (duty <= 0) {
                duty = 0;
                step = 1;
            }
        }
    }
}
