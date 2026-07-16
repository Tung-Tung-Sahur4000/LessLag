package tk.bridgersilk.lesslag.performance;

import org.bukkit.Bukkit;

/**
 * Central source of truth for the "is the server lagging" decision used by
 * every reactive feature.
 *
 * <p>Historically the reactive features gated on {@code getTPS()[0]}, the
 * <b>1-minute average</b> TPS. That only moves after lag has been sustained
 * for many seconds, so it reacts to sustained load but completely misses
 * short spikes (a lag burst is over before the 1-minute average notices).
 *
 * <p>The fix is to derive an <i>instantaneous</i> TPS from MSPT
 * ({@link org.bukkit.Server#getAverageTickTime()}, a rolling average over the
 * last ~100 ticks / ~5s) which reflects a spike within seconds. The reactive
 * signal fires when <b>either</b> the MSPT-derived TPS <b>or</b> the 1-minute
 * average is below the threshold — a strict superset of the old behaviour, so
 * it is strictly more responsive and never less protective than before.
 */
public class TPSUtil {

    /** 1-minute rolling average TPS (sustained lag). Clamped to [0, 20]. */
    public static double getAverageTPS() {
        double[] tps = Bukkit.getTPS();
        return tps.length > 0 ? clamp(tps[0]) : 20.0;
    }

    /** Average tick time in ms over the last ~5s (MSPT). */
    public static double getMSPT() {
        return Bukkit.getAverageTickTime();
    }

    /**
     * Instantaneous TPS derived from MSPT. A tick budget is 50ms, so anything
     * at or under 50ms is a healthy 20 TPS; above that the effective TPS is
     * {@code 1000 / mspt}.
     */
    public static double getInstantTPS() {
        double mspt = getMSPT();
        return mspt > 0 ? clamp(1000.0 / mspt) : 20.0;
    }

    /**
     * Spike-aware reactive signal: the lower of the instantaneous (MSPT) and
     * the 1-minute-average TPS. Reacting on the minimum means a short spike
     * (low instant TPS) trips it immediately, while sustained lag (low
     * average) still trips it as it always did.
     */
    public static double getResponsiveTPS() {
        return Math.min(getInstantTPS(), getAverageTPS());
    }

    /** True when the responsive signal is below the given threshold. */
    public static boolean isLagging(double tpsThreshold) {
        return getResponsiveTPS() < tpsThreshold;
    }

    /**
     * Back-compat entry point. Existing reactive callers used this as their
     * lag gate, so it now returns the spike-aware responsive signal — every
     * feature routed through it becomes spike-reactive with no call-site
     * change. Use {@link #getAverageTPS()} explicitly where the slow,
     * sustained signal is intentionally wanted (e.g. drastic, hard-to-reverse
     * actions like world unloads).
     */
    public static double getTPS() {
        return getResponsiveTPS();
    }

    private static double clamp(double tps) {
        return Math.max(0.0, Math.min(20.0, tps));
    }
}
