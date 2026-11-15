package com.fluxtv.app;

import java.util.ArrayList;
import java.util.List;

/** This build doesn't include Google's Home APIs SDK (see app/build.gradle), so Google Home isn't available. */
public final class GoogleHomeProvider {
    private GoogleHomeProvider() { }

    public static boolean included() { return false; }
    public static boolean signedIn() { return false; }
    public static String status() { return "Not included in this build of Flux"; }
    public static List<SmartHome.Device> devices() { return new ArrayList<>(); }
    public static void setOn(SmartHome.Device d, boolean on) { }
    public static void setLevel(SmartHome.Device d, int level) { }
    public static void signOut() { }
}
