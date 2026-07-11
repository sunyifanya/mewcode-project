package com.mewcode.teams;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Tmux backend for spawning teammates in separate tmux windows.
 */
public final class TmuxBackend {

    private static final Logger log = Logger.getLogger(TmuxBackend.class.getName());

    private TmuxBackend() {}

    /**
     * Spawns a new tmux window running the given CLI command.
     *
     * @param teamName   the team name (used in the window title)
     * @param memberName the member name (used in the window title)
     * @param cliCommand the shell command to run in the new window
     * @return the tmux window/pane name
     */
    public static String spawnTmuxTeammate(String teamName, String memberName, String cliCommand) throws Exception {
        String paneName = teamName + "-" + memberName;
        var pb = new ProcessBuilder("tmux", "new-window", "-d", "-n", paneName, cliCommand);
        pb.redirectErrorStream(true);
        var proc = pb.start();
        proc.getInputStream().readAllBytes();
        boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
        if (!finished || proc.exitValue() != 0) {
            throw new RuntimeException("Failed to spawn tmux window: " + paneName);
        }
        return paneName;
    }

    /**
     * Stops a tmux teammate by sending Ctrl-C and then killing the window.
     */
    public static void stopTmuxTeammate(String paneName) {
        try {
            // Send Ctrl-C first
            new ProcessBuilder("tmux", "send-keys", "-t", paneName, "C-c")
                    .start().waitFor(5, TimeUnit.SECONDS);
            Thread.sleep(200);
            // Then kill the window
            new ProcessBuilder("tmux", "kill-window", "-t", paneName)
                    .start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.fine("Failed to stop tmux teammate: " + e.getMessage());
        }
    }
}
