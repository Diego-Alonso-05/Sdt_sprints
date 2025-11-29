package common;

// Global logger so every component can print logs safely.
public class AppLog {

    private static java.util.function.Consumer<String> sink = System.out::println;

    // Called by TCPClient to redirect logs to its Swing JTextArea
    public static void setSink(java.util.function.Consumer<String> newSink) {
        sink = newSink;
    }

    // Global log entry point used by all system components
    public static void log(String msg) {
        try {
            sink.accept(msg);
        } catch (Exception e) {
            System.out.println(msg);
        }
    }
}
