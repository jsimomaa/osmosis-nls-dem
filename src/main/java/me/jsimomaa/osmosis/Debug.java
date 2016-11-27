package me.jsimomaa.osmosis;

import me.jsimomaa.osmosis.utils.TM35Utils;
import me.jsimomaa.osmosis.utils.TM35Utils.TM35Scale;

public class Debug {
    
    
    public static void main(String[] args) {
        String result = TM35Utils.reverseGeocode(368000, 6678000, TM35Scale.SCALE_10000);
        System.out.println(result);
    }
}
