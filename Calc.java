package com.finplan;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure calculation logic. Tax slabs are data-driven: update them each budget cycle. */
public final class Calc {
    private Calc() {}

    // {upper bound of slab, rate}. Verify against the current Finance Act before relying on them.
    static final double[][] NEW_SLABS = {{400000,0},{800000,.05},{1200000,.10},{1600000,.15},{2000000,.20},{2400000,.25},{Double.MAX_VALUE,.30}};
    static final double[][] OLD_SLABS = {{250000,0},{500000,.05},{1000000,.20},{Double.MAX_VALUE,.30}};

    static double v(Map<String, Double> m, String k, double def) { return m.getOrDefault(k, def); }
    static double r2(double x) { return Math.round(x * 100.0) / 100.0; }

    static double sipFv(double monthly, double annualPct, int years, double stepUpPct) {
        double i = annualPct / 1200, bal = 0, m = monthly;
        for (int y = 0; y < years; y++) { for (int k = 0; k < 12; k++) bal = (bal + m) * (1 + i); m *= 1 + stepUpPct / 100; }
        return bal;
    }

    static double sipNeeded(double fv, double annualPct, int months) {
        if (fv <= 0 || months <= 0) return 0;
        double i = annualPct / 1200;
        return i == 0 ? fv / months : fv * i / ((Math.pow(1 + i, months) - 1) * (1 + i));
    }

    static double slabTax(double income, double[][] slabs) {
        double tax = 0, prev = 0;
        for (double[] s : slabs) { if (income > prev) tax += (Math.min(income, s[0]) - prev) * s[1]; prev = s[0]; }
        return tax;
    }

    public static Map<String, Object> run(String module, Map<String, Double> in) {
        var o = new LinkedHashMap<String, Object>();
        switch (module) {
            case "sip" -> {
                int y = (int) v(in, "years", 10);
                double m = v(in, "monthly", 10000), step = v(in, "stepUp", 0);
                double fv = sipFv(m, v(in, "returnPct", 12), y, step), inv = 0, mm = m;
                for (int i = 0; i < y; i++) { inv += mm * 12; mm *= 1 + step / 100; }
                o.put("Total invested", r2(inv)); o.put("Future value", r2(fv)); o.put("Gain", r2(fv - inv));
                o.put("Inflation-adjusted value", r2(fv / Math.pow(1 + v(in, "inflation", 6) / 100, y)));
            }
            case "goal" -> {
                int y = (int) v(in, "years", 10); double r = v(in, "returnPct", 10);
                double fc = v(in, "cost", 1000000) * Math.pow(1 + v(in, "inflation", 6) / 100, y);
                double need = fc - v(in, "existing", 0) * Math.pow(1 + r / 100, y);
                o.put("Goal cost at target date", r2(fc)); o.put("Required monthly SIP", r2(sipNeeded(need, r, y * 12)));
            }
            case "retirement" -> {
                int age = (int) v(in, "age", 30), ret = (int) v(in, "retireAge", 60), life = (int) v(in, "lifeExpectancy", 85);
                double inf = v(in, "inflation", 6) / 100;
                double annual = v(in, "monthlyExpense", 50000) * 12 * Math.pow(1 + inf, ret - age);
                double rr = (1 + v(in, "postReturn", 8) / 100) / (1 + inf) - 1; int n = life - ret;
                double corpus = Math.abs(rr) < 1e-9 ? annual * n : annual * (1 - Math.pow(1 + rr, -n)) / rr;
                double r = v(in, "preReturn", 11);
                double gap = corpus - v(in, "existing", 0) * Math.pow(1 + r / 100, ret - age);
                o.put("Annual expense at retirement", r2(annual)); o.put("Corpus required", r2(corpus));
                o.put("Monthly SIP required", r2(sipNeeded(gap, r, (ret - age) * 12)));
            }
            case "tax" -> {
                double gross = v(in, "income", 1500000), ded = v(in, "deductions", 150000);
                double newTaxable = Math.max(0, gross - 75000), oldTaxable = Math.max(0, gross - 50000 - ded);
                double nt = slabTax(newTaxable, NEW_SLABS); if (newTaxable <= 1200000) nt = 0;
                double ot = slabTax(oldTaxable, OLD_SLABS); if (oldTaxable <= 500000) ot = 0;
                nt *= 1.04; ot *= 1.04;
                o.put("New regime tax (incl. 4% cess)", r2(nt)); o.put("Old regime tax (incl. 4% cess)", r2(ot));
                o.put("Better option", nt <= ot ? "New regime" : "Old regime"); o.put("Savings", r2(Math.abs(nt - ot)));
            }
            case "insurance" -> {
                double inc = v(in, "income", 1200000); int yrs = Math.max(1, (int) (v(in, "retireAge", 60) - v(in, "age", 30)));
                double rr = 0.02, pv = inc * 0.7 * (1 - Math.pow(1 + rr, -yrs)) / rr;
                double term = Math.max(0, pv + v(in, "liabilities", 0) - v(in, "existingCover", 0) - v(in, "savings", 0));
                o.put("Recommended term cover", r2(term));
                o.put("Recommended health cover (family floater)", r2(Math.max(1000000, 500000 * (1 + v(in, "dependents", 0)))));
                o.put("Rule of thumb (15x income)", r2(inc * 15));
            }
            case "emergency" -> {
                double months = v(in, "dependents", 0) > 0 || v(in, "variableIncome", 0) > 0 ? 9 : 6;
                double target = (v(in, "expenses", 40000) + v(in, "emi", 0)) * months;
                double gap = Math.max(0, target - v(in, "current", 0));
                o.put("Months of cover", months); o.put("Target fund", r2(target)); o.put("Shortfall", r2(gap));
                o.put("Monthly to close in 12 months", r2(gap / 12));
            }
            case "allocation" -> {
                int age = (int) v(in, "age", 30); int risk = (int) v(in, "risk", 2); // 1 low, 2 moderate, 3 high
                double eq = Math.max(20, Math.min(90, 100 - age + (risk - 2) * 15)), gold = 10, debt = 100 - eq - gold;
                o.put("Equity %", eq); o.put("Debt %", debt); o.put("Gold %", gold);
                o.put("Expected return %", r2(eq * .12 + debt * .07 + gold * .08));
            }
            case "emi" -> {
                double p = v(in, "principal", 5000000), i = v(in, "ratePct", 8.5) / 1200; int n = (int) v(in, "years", 20) * 12;
                double emi = i == 0 ? p / n : p * i * Math.pow(1 + i, n) / (Math.pow(1 + i, n) - 1);
                o.put("Monthly EMI", r2(emi)); o.put("Total interest", r2(emi * n - p)); o.put("Total payment", r2(emi * n));
            }
            default -> throw new IllegalArgumentException("Unknown module: " + module);
        }
        return o;
    }
}
