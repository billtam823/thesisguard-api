package com.thesisguard.openbb;

// Best-effort valuation/revenue snapshot fed into the buy-thesis prompt so the growth forecast
// can be computed from real data. Any field may be null when OpenBB is rate-limited or thin on
// coverage; the prompt degrades to a low-confidence estimate in that case.
public record FundamentalsSnapshot(
        Double priceToSales,
        Double peRatio,
        Double marketCap,
        Double latestRevenue,
        Double priorRevenue
) {
    public boolean hasAny() {
        return priceToSales != null || peRatio != null || marketCap != null
                || latestRevenue != null || priorRevenue != null;
    }

    /** Latest-vs-prior annual revenue growth in percent, or null when either year is missing. */
    public Double historicalRevenueGrowthPct() {
        if (latestRevenue == null || priorRevenue == null || priorRevenue == 0d) {
            return null;
        }
        return (latestRevenue - priorRevenue) / Math.abs(priorRevenue) * 100d;
    }
}
