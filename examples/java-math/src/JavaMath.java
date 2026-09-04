import com.github.pfichtner.espressomachine.api.Serial;

public class JavaMath {
    public static void main(String[] args) {
        Serial.begin(9600);
        int a = 5;
        int b = 3;
        Serial.println(Math.min(a, b));
        Serial.println(Math.max(a, b));
        Serial.println(Math.abs(-7));
        double p = Math.pow(2.0, 10.0);
        double s = Math.sqrt(9.0);
        Serial.println((int) p);
        Serial.println((int) s);
    }
}
