// EspressoMachine enum support test.
//
// Supported today:
//   - ordinal() access (reads the i32 ordinal field)
//   - == / != comparison (pointer equality on singleton globals)
//   - custom primitive fields (getelementptr into enum struct)
//
// Not supported (compile error emitted):
//   - name(), toString()     — requires String heap
//   - valueOf(String)        — requires String heap + reflection
//   - values()               — requires array heap
//
// Limitation: switch(enumVar) {} uses a synthetic $SwitchMap int[] array
// that requires heap initialization. Use if/else chains instead.

enum Direction { NORTH, SOUTH, EAST, WEST }

enum Pin {
    LED(13), BUTTON(2);
    final int number;
    Pin(int n) { this.number = n; }
}

class EnumTest {

    // Ordinal access: reads the i32 ordinal field from the Enum struct.
    static int ordinalOf(Direction d) {
        return d.ordinal();
    }

    // Enum comparison: pointer equality against the singleton global.
    static boolean isNorth(Direction d) {
        return d == Direction.NORTH;
    }

    // if/else on enum (works; ordinal-based comparison).
    static int encode(Direction d) {
        if (d == Direction.NORTH) return 0;
        if (d == Direction.SOUTH) return 1;
        if (d == Direction.EAST)  return 2;
        return 3;
    }

    // Custom enum field: accesses Pin.number via getelementptr.
    static int pinNumber(Pin p) {
        return p.number;
    }

    public static void main(String[] args) {
        ordinalOf(Direction.EAST);
        isNorth(Direction.NORTH);
        encode(Direction.WEST);
        pinNumber(Pin.LED);
    }
}
