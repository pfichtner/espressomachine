import java.util.Random;
import com.github.pfichtner.espressomachine.api.Serial;

public class JavaRandom {
    public static void main(String[] args) {
        Serial.begin(9600);
        Random rng = new Random();
        int a = rng.nextInt();
        int b = rng.nextInt(100);
        long c = rng.nextLong();
        Serial.println(a);
        Serial.println(b);
        Serial.println((int) c);
    }
}
