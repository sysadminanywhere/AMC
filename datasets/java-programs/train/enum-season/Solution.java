public class Solution {
    public enum Season { SPRING, SUMMER, AUTUMN, WINTER }

    public static boolean cold(Season season) {
        return season == Season.WINTER;
    }
}
