import com.github.pfichtner.espressomachine.api.Serial;
import com.github.pfichtner.espressomachine.api.Random;

public class RandomExample {
    public static void main(String[] args) {
        Serial.begin(9600);
        int a = Random.random(100);
        int b = Random.random(1, 10);
        Serial.println(a);
        Serial.println(b);
    }
}
