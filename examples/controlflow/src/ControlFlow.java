class ControlFlow {
    static int test(int x) {
        if (x > 10) return 1;
        else return 0;
    }

    static int count() {
        int x = 0;
        while (x < 10) x++;
        return x;
    }

    public static void main(String[] args) {
        test(5);
        count();
    }
}
