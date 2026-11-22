public class Solution {
    public abstract static class Animal {
        public abstract String sound();
    }

    public static class Dog extends Animal {
        @Override
        public String sound() {
            return "woof";
        }
    }
}
