package org.mitre.synthea.modules;

import java.util.Locale;
import java.util.Map;

import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.HealthRecord;
import org.mitre.synthea.world.concepts.VitalSign;

/**
 * Stroke score calculator backed by multivariable regression-style models.
 */
public final class StrokeScoreCalculator {
  private static final String AFIB_CODE = "49436004";
  private static final String STROKE_CODE = "230690007";
  private static final String ICH_CODE = "230692009";
  private static final String TIA_CODE = "266257000";

  private StrokeScoreCalculator() {
  }

  public enum ScoreType {
    NIHSS,
    MRS;

    /**
     * Parse a score type from JSON.
     * @param value score type text
     * @return score type
     */
    public static ScoreType fromString(String value) {
      if (value == null) {
        throw new IllegalArgumentException("score_type is required");
      }
      return ScoreType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
  }

  public enum Timepoint {
    BASELINE,
    ADMISSION,
    THREE_MONTH,
    SIX_MONTH;

    /**
     * Parse a timepoint from JSON or a temporary patient attribute.
     * @param value timepoint text
     * @return timepoint
     */
    public static Timepoint fromString(String value) {
      if (value == null) {
        throw new IllegalArgumentException("stroke score timepoint is required");
      }
      String normalized = value.trim().toUpperCase(Locale.ROOT)
          .replace('-', '_')
          .replace(' ', '_');
      return Timepoint.valueOf(normalized);
    }
  }

  /**
   * Compute a stroke clinical score.
   * @param person patient
   * @param time simulation time
   * @param scoreType target score
   * @param timepoint timepoint for the score
   * @return integer score
   */
  public static int computeScore(Person person, long time, ScoreType scoreType, Timepoint timepoint) {
    StrokeFeatures features = new StrokeFeatures(person, time);
    switch (scoreType) {
      case NIHSS:
        return computeNihss(person, features, timepoint);
      case MRS:
        return computeMrs(person, features, timepoint);
      default:
        throw new IllegalArgumentException("Unsupported stroke score: " + scoreType);
    }
  }

  private static int computeNihss(Person person, StrokeFeatures f, Timepoint timepoint) {
    double score;
    switch (timepoint) {
      case ADMISSION:
        score = 4.2;
        score += positivePart(f.ageYears - 45.0) * 0.085;
        score += f.isHemorrhagic ? 6.0 : 0.0;
        score += f.isIschemic ? 1.8 : 0.0;
        score += f.isTia ? -5.5 : 0.0;
        score += f.hasPriorStrokeHistory ? 1.2 : 0.0;
        score += f.hasHypertension ? 1.0 : 0.0;
        score += f.hasDiabetes ? 1.1 : 0.0;
        score += f.hasObesity ? 0.6 : 0.0;
        score += f.hasHyperlipidemia ? 0.4 : 0.0;
        score += f.isSmoker ? 0.8 : 0.0;
        score += f.hasAtrialFibrillation ? 1.9 : 0.0;
        score += f.cardioembolicRisk ? 1.4 : 0.0;
        score += f.thrombectomyCandidate ? 2.6 : 0.0;
        score += f.largeVesselOcclusion ? 3.8 : 0.0;
        score += f.hemorrhageOnImaging ? 4.8 : 0.0;
        score += f.earlyIschemicChange ? 1.4 : 0.0;
        score += f.cardioembolicSourceIdentified ? 0.9 : 0.0;
        score += toastContribution(f.toastType);
        score += positivePart(f.systolicBp - 140.0) * 0.020;
        score += positivePart(f.diastolicBp - 90.0) * 0.015;
        score += positivePart(f.glucose - 110.0) * 0.018;
        score += positivePart(f.wbc - 8.0) * 0.22;
        score += positivePart(f.inr - 1.1) * 2.5;
        score += positivePart(f.troponin - 0.02) * 20.0;
        score += positivePart(f.bmi - 30.0) * 0.10;
        score += gaussian(person, 0.0, 1.8);
        break;
      case THREE_MONTH:
        score = 0.35 * Math.max(f.nihssAdmission, 0.0);
        score += 0.45 * Math.max(f.baselineMrs, 0.0);
        score += f.isHemorrhagic ? 1.8 : 0.0;
        score += f.isTia ? -1.8 : 0.0;
        score += f.hasThrombolytic ? -1.7 : 0.0;
        score += f.hasThrombectomyProcedure ? -2.8 : 0.0;
        score += f.hasAntiplatelet ? -0.7 : 0.0;
        score += f.hasBpLoweringTreatment ? -0.6 : 0.0;
        score += f.hasStatin ? -0.5 : 0.0;
        score += f.cardioembolicRisk ? 0.6 : 0.0;
        score += positivePart(f.glucose - 110.0) * 0.010;
        score += positivePart(f.inr - 1.1) * 1.2;
        score += gaussian(person, 0.0, 1.4);
        break;
      default:
        throw new IllegalArgumentException("NIHSS is not defined for timepoint " + timepoint);
    }
    return clampAndRound(score, 0, 42);
  }

  private static int computeMrs(Person person, StrokeFeatures f, Timepoint timepoint) {
    if (!person.alive(f.time)) {
      return 6;
    }

    double latent;
    switch (timepoint) {
      case BASELINE:
        latent = 0.10;
        latent += positivePart(f.ageYears - 60.0) * 0.012;
        latent += f.hasHypertension ? 0.20 : 0.0;
        latent += f.hasDiabetes ? 0.20 : 0.0;
        latent += f.hasObesity ? 0.10 : 0.0;
        latent += f.hasPriorStrokeHistory ? 0.60 : 0.0;
        latent += gaussian(person, 0.0, 0.35);
        return clampAndRound(latent, 0, 3);
      case THREE_MONTH:
        latent = -0.20;
        latent += 0.09 * Math.max(f.nihssAdmission, 0.0);
        latent += 0.18 * Math.max(f.nihssThreeMonth, 0.0);
        latent += 0.42 * Math.max(f.baselineMrs, 0.0);
        latent += f.isHemorrhagic ? 0.70 : 0.0;
        latent += f.isTia ? -0.80 : 0.0;
        latent += f.hasPriorStrokeHistory ? 0.45 : 0.0;
        latent += f.hasThrombolytic ? -0.35 : 0.0;
        latent += f.hasThrombectomyProcedure ? -0.70 : 0.0;
        latent += f.hasAntiplatelet ? -0.15 : 0.0;
        latent += f.hasStatin ? -0.10 : 0.0;
        latent += positivePart(f.glucose - 110.0) * 0.004;
        latent += gaussian(person, 0.0, 0.55);
        return clampAndRound(latent, 0, 6);
      case SIX_MONTH:
        latent = -0.35;
        latent += 0.14 * Math.max(f.mrsThreeMonth, 0.0);
        latent += 0.12 * Math.max(f.nihssThreeMonth, 0.0);
        latent += 0.24 * Math.max(f.baselineMrs, 0.0);
        latent += f.isHemorrhagic ? 0.35 : 0.0;
        latent += f.isTia ? -0.40 : 0.0;
        latent += f.hasPriorStrokeHistory ? 0.30 : 0.0;
        latent += f.hasThrombolytic ? -0.18 : 0.0;
        latent += f.hasThrombectomyProcedure ? -0.40 : 0.0;
        latent += f.hasBpLoweringTreatment ? -0.10 : 0.0;
        latent += gaussian(person, 0.0, 0.45);
        return clampAndRound(latent, 0, 6);
      default:
        throw new IllegalArgumentException("mRS is not defined for timepoint " + timepoint);
    }
  }

  private static double toastContribution(String toastType) {
    if (toastType == null) {
      return 0.0;
    }
    String normalized = toastType.toLowerCase(Locale.ROOT);
    if (normalized.contains("cardioembolic")) {
      return 1.8;
    } else if (normalized.contains("large artery")) {
      return 1.2;
    } else if (normalized.contains("small vessel")) {
      return -0.8;
    } else if (normalized.contains("other")) {
      return 0.4;
    }
    return 0.0;
  }

  private static double gaussian(Person person, double mean, double standardDeviation) {
    return mean + (person.randGaussian() * standardDeviation);
  }

  private static double positivePart(double value) {
    return Math.max(value, 0.0);
  }

  private static int clampAndRound(double value, int min, int max) {
    return (int) Math.max(min, Math.min(max, Math.round(value)));
  }

  private static final class StrokeFeatures {
    private final long time;
    private final double ageYears;
    private final double systolicBp;
    private final double diastolicBp;
    private final double bmi;
    private final double wbc;
    private final double glucose;
    private final double inr;
    private final double troponin;
    private final double baselineMrs;
    private final double nihssAdmission;
    private final double nihssThreeMonth;
    private final double mrsThreeMonth;
    private final String strokeType;
    private final String toastType;
    private final boolean isTia;
    private final boolean isIschemic;
    private final boolean isHemorrhagic;
    private final boolean isSmoker;
    private final boolean hasHypertension;
    private final boolean hasDiabetes;
    private final boolean hasObesity;
    private final boolean hasHyperlipidemia;
    private final boolean cardioembolicRisk;
    private final boolean thrombectomyCandidate;
    private final boolean hemorrhageOnImaging;
    private final boolean earlyIschemicChange;
    private final boolean largeVesselOcclusion;
    private final boolean cardioembolicSourceIdentified;
    private final boolean hasAtrialFibrillation;
    private final boolean hasThrombolytic;
    private final boolean hasAntiplatelet;
    private final boolean hasStatin;
    private final boolean hasBpLoweringTreatment;
    private final boolean hasThrombectomyProcedure;
    private final boolean hasPriorStrokeHistory;

    private StrokeFeatures(Person person, long time) {
      this.time = time;
      this.ageYears = person.ageInDecimalYears(time);
      this.systolicBp = safeVitalSign(person, VitalSign.SYSTOLIC_BLOOD_PRESSURE, time);
      this.diastolicBp = safeVitalSign(person, VitalSign.DIASTOLIC_BLOOD_PRESSURE, time);
      this.bmi = safeVitalSign(person, VitalSign.BMI, time);
      this.wbc = numberAttribute(person, "wbc_count", 7.5);
      this.glucose = numberAttribute(person, "glucose_level", 100.0);
      this.inr = numberAttribute(person, "inr_value", 1.0);
      this.troponin = numberAttribute(person, "troponin_level", 0.01);
      this.baselineMrs = numberAttribute(person, "baseline_mrs", 0.0);
      this.nihssAdmission = numberAttribute(person, "nihss_admission", 0.0);
      this.nihssThreeMonth = numberAttribute(person, "nihss_3_month", 0.0);
      this.mrsThreeMonth = numberAttribute(person, "mRS_3_month", 0.0);
      this.strokeType = stringAttribute(person, "stroke_type");
      this.toastType = stringAttribute(person, "stroke_toast_type");
      this.isTia = "TIA".equalsIgnoreCase(strokeType) || hasActiveCode(person, TIA_CODE);
      this.isIschemic =
          "Ischemic".equalsIgnoreCase(strokeType) || hasActiveCode(person, STROKE_CODE);
      this.isHemorrhagic =
          "Hemorrhagic".equalsIgnoreCase(strokeType) || hasActiveCode(person, ICH_CODE);
      this.isSmoker = booleanAttribute(person, Person.SMOKER);
      this.hasHypertension = booleanAttribute(person, "hypertension");
      this.hasDiabetes = booleanAttribute(person, "diabetes");
      this.hasObesity = booleanAttribute(person, "obesity");
      this.hasHyperlipidemia =
          booleanAttribute(person, "hyperlipidemia")
          || person.attributes.containsKey("hyperlipidemia");
      this.cardioembolicRisk = booleanAttribute(person, "cardioembolic_risk");
      this.thrombectomyCandidate = booleanAttribute(person, "thrombectomy_candidate");
      this.hemorrhageOnImaging = booleanAttribute(person, "hemorrhage_on_imaging")
          || containsText(stringAttribute(person, "ncct_finding"), "hyperdense lesion");
      this.earlyIschemicChange = booleanAttribute(person, "early_ischemic_change")
          || containsText(stringAttribute(person, "ncct_finding"), "early ischemic changes");
      this.largeVesselOcclusion = booleanAttribute(person, "large_vessel_occlusion")
          || containsText(stringAttribute(person, "cta_finding"), "large vessel occlusion");
      this.cardioembolicSourceIdentified =
          booleanAttribute(person, "cardioembolic_source_identified")
          || containsText(stringAttribute(person, "tee_finding"), "thrombus")
          || containsText(stringAttribute(person, "tee_finding"), "patent foramen ovale")
          || containsText(stringAttribute(person, "tee_finding"), "vegetations");
      this.hasAtrialFibrillation =
          booleanAttribute(person, "atrial_fibrillation_detected")
          || cardioembolicRisk
          || person.attributes.containsKey("atrial_fibrillation")
          || hasActiveCode(person, AFIB_CODE)
          || containsText(stringAttribute(person, "ecg_finding"), "atrial fibrillation");
      this.hasThrombolytic = person.attributes.containsKey("stroke_medication_thrombolytic");
      this.hasAntiplatelet = person.attributes.containsKey("stroke_medication_antiplatelet");
      this.hasStatin = person.attributes.containsKey("statin");
      this.hasBpLoweringTreatment = booleanAttribute(person, "stroke_medication_bp_lowering")
          || person.attributes.containsKey("hypertension_medication")
          || person.attributes.containsKey("hypertension_medication_2")
          || person.attributes.containsKey("hypertension_medication_3")
          || person.attributes.containsKey("hypertension_medication_4");
      this.hasThrombectomyProcedure = person.attributes.containsKey("stroke_procedure_thrombectomy");
      this.hasPriorStrokeHistory =
          person.attributes.containsKey("stroke_history")
          || activeStrokeConditionCount(person) > 1;
    }

    private static boolean containsText(String text, String expectedFragment) {
      return text != null && text.toLowerCase(Locale.ROOT).contains(expectedFragment);
    }

    private static String stringAttribute(Person person, String attribute) {
      Object value = person.attributes.get(attribute);
      if (value == null) {
        return null;
      }
      return value.toString();
    }

    private static double numberAttribute(Person person, String attribute, double defaultValue) {
      Object value = person.attributes.get(attribute);
      if (value instanceof Number) {
        return ((Number) value).doubleValue();
      }
      return defaultValue;
    }

    private static boolean booleanAttribute(Person person, String attribute) {
      Object value = person.attributes.get(attribute);
      if (value instanceof Boolean) {
        return (Boolean) value;
      }
      return false;
    }

    private static double safeVitalSign(Person person, VitalSign vitalSign, long time) {
      try {
        return person.getVitalSign(vitalSign, time);
      } catch (NullPointerException ex) {
        return 0.0;
      }
    }

    private static boolean hasActiveCode(Person person, String code) {
      for (Map.Entry<String, HealthRecord.Entry> entry : person.record.present.entrySet()) {
        if (code.equals(entry.getKey())) {
          return true;
        }
        if (entry.getValue() != null && entry.getValue().containsCode(code, "SNOMED-CT")) {
          return true;
        }
      }
      return false;
    }

    private static int activeStrokeConditionCount(Person person) {
      int count = 0;
      for (HealthRecord.Entry entry : person.record.present.values()) {
        if (entry != null
            && (entry.containsCode(STROKE_CODE, "SNOMED-CT")
            || entry.containsCode(ICH_CODE, "SNOMED-CT")
            || entry.containsCode(TIA_CODE, "SNOMED-CT"))) {
          count++;
        }
      }
      return count;
    }
  }
}
