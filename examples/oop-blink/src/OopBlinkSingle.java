import com.github.pfichtner.espressomachine.api.*;

class OopBlinkSingle {
    public static void main(String[] args) {
        Led led = new Led(13);

        while (true) {
            led.on();
            Delay.delay(500);
            led.off();
            Delay.delay(500);
        }
    }
}
