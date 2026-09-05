import com.github.pfichtner.espressomachine.api.*;

class OopBlink {
    static void main() {
        Led led1 = new Led(13);
        Led led2 = new Led(12);

        while (true) {
            led1.on();
            led2.off();
            Delay.delay(500);

            led1.off();
            led2.on();
            Delay.delay(500);
        }
    }
}
