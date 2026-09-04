import com.github.pfichtner.espressomachine.api.*;

class OopBlink {
    static void main() {
        Led led = new Led(13);

        while (true) {
            led.on();
            Delay.delay(500);
            led.off();
            Delay.delay(500);
        }
    }
}
