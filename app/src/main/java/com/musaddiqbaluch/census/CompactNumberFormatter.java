package com.musaddiqbaluch.census;

import java.util.Locale;

/**
 * Compact number formatting: 1234 -> "1.2K", 999999 -> "1M",
 * 1_500_000_000 -> "1.5B".
 *
 * Contract
 * --------
 *   - Never throws. Any internal failure falls back to String.valueOf.
 *   - Negative values are clamped to 0.
 *   - At most three significant digits before the suffix:
 *       1_000     -> 1K
 *       1_100     -> 1.1K
 *       999_500   -> 999.5K
 *       999_950   -> 1M       (rounding promotes to the next unit,
 *       999_999   -> 1M        matching Google's CompactDecimalFormat)
 *       1_000_000 -> 1M
 *
 * Locale
 * ------
 *   Suffixes K / M / B are English shorthand. Using Locale.US for the
 *   decimal separator keeps the output self-consistent (a dot always
 *   separates the decimal) regardless of the device's locale. This
 *   matches how Android's own CompactDecimalFormat behaves for the
 *   English abbreviations.
 */
public final class CompactNumberFormatter {

    private CompactNumberFormatter() {
        // Utility class.
    }

    // ============================================================
    // Public API
    // ============================================================

    public static String format(int value) {
        return format((long) value);
    }

    public static String format(long value) {

        try {
            if (value < 0L) {
                value = 0L;
            }

            // Under 1K -> plain integer, no suffix.
            if (value < 1_000L) {
                return String.valueOf(value);
            }

            // Under 1M -> K, unless rounding pushes us into M territory.
            if (value < 1_000_000L) {
                double k = value / 1_000.0;
                if (roundToOneDecimal(k) < 1_000.0) {
                    return formatRounded(k, "K");
                }
                // 999_950 and above: fall through to M.
            }

            // Under 1B -> M, unless rounding pushes us into B territory.
            if (value < 1_000_000_000L) {
                double m = value / 1_000_000.0;
                if (roundToOneDecimal(m) < 1_000.0) {
                    return formatRounded(m, "M");
                }
                // 999_950_000 and above: fall through to B.
            }

            // 1B and above -> B. Values above ~1 trillion will show a
            // large mantissa (e.g. "1500B"), but that is out of range
            // for the app-count use case this formatter serves.
            double b = value / 1_000_000_000.0;
            return formatRounded(b, "B");

        } catch (Throwable t) {
            // Deterministic formatter; this should never fire. Kept so
            // a future change cannot accidentally turn this into a
            // crash path for callers.
            return String.valueOf(value);
        }
    }

    // ============================================================
    // Internal
    // ============================================================

    /**
     * Rounds a double to one decimal place using the same rule the
     * formatter displays with: Math.round(v * 10) / 10.
     */
    private static double roundToOneDecimal(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * Formats a value that has already been confirmed to be below the
     * next unit's rounding threshold. Strips a trailing ".0" so
     * whole-number results render as "1K" rather than "1.0K".
     */
    private static String formatRounded(double raw, String suffix) {

        double rounded = roundToOneDecimal(raw);

        String result = String.format(
                Locale.US,
                "%.1f",
                rounded
        );

        // "1.0" -> "1"
        if (result.endsWith(".0")) {
            result = result.substring(0, result.length() - 2);
        }

        return result + suffix;
    }
}