import com.github.pfichtner.espressomachine.api.*;

class OopBlink {
    public static void main(String[] args) {
        Led led1 = new Led(13);
        Led led2 = new Led(12);

        while (true) {
            // Turn led2 off before turning led1 on so the two LEDs are never
            // HIGH at the same time (systemtest-oop-blink asserts this).
            led2.off();
            led1.on();
            Delay.delay(500);

            // Likewise: clear led1 before driving led2.
            led1.off();
            led2.on();
            Delay.delay(500);
        }
    }
}
