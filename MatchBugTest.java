import java.nio.file.*;

public class MatchBugTest {
    public static void main(String[] args) {
        // Simulate what happens when loop rule is added with raw file_path
        String rawPath = "D:/code/mewcode/mewcode/syf.txt";
        
        // How loop rule stores it (UNESCAPED)
        String loopPattern = rawPath;  // no escaping
        
        // How addAllowAlways stores it (ESCAPED)
        String escaped = rawPath
                .replace("\\", "\\\\")
                .replace("*", "\*")
                .replace("?", "\?")
                .replace("[", "\[");
        
        Path target = Paths.get(rawPath);
        
        System.out.println("Target: " + target);
        System.out.println();
        
        // Test unescaped glob match
        try {
            PathMatcher m1 = FileSystems.getDefault().getPathMatcher("glob:" + loopPattern);
            System.out.println("UNESCAPED glob:" + loopPattern);
            System.out.println("  matches: " + m1.matches(target));
        } catch (Exception e) {
            System.out.println("UNESCAPED ERROR: " + e.getMessage());
        }
        
        // Test escaped glob match
        try {
            PathMatcher m2 = FileSystems.getDefault().getPathMatcher("glob:" + escaped);
            System.out.println("ESCAPED   glob:" + escaped);
            System.out.println("  matches: " + m2.matches(target));
        } catch (Exception e) {
            System.out.println("ESCAPED ERROR: " + e.getMessage());
        }
        
        // What if we use forward slashes?
        String fwdPath = "D:/code/mewcode/mewcode/syf.txt";
        try {
            PathMatcher m3 = FileSystems.getDefault().getPathMatcher("glob:" + fwdPath);
            System.out.println("FWD SLASH glob:" + fwdPath);
            System.out.println("  matches: " + m3.matches(target));
        } catch (Exception e) {
            System.out.println("FWD SLASH ERROR: " + e.getMessage());
        }
    }
}
