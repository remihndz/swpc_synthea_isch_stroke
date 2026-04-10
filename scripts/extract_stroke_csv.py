#!/usr/bin/env python3
"""
Extract stroke-related events from Synthea CSV output.

This script:
1. Identifies each patient's earliest stroke event from conditions.csv
2. Filters post-stroke observations relevant to investigations and clinical scores
3. Writes both long-form event rows and a patient-level score summary

Example:
  python scripts/extract_stroke_csv.py --input-dir output/csv --output-dir output/stroke_extract
"""

from __future__ import annotations

import argparse
import csv
import os
from collections import defaultdict
from datetime import datetime, timezone
from typing import Dict, Iterable, List, Tuple


STROKE_CONDITION_CODES = {
    "266257000": "TIA",
    "230690007": "Ischemic",
    "230692009": "Hemorrhagic",
}

SCORE_CODES = {
    "9740-3": "NIHSS",
    "72542-0": "mRS",
}

INVESTIGATION_CODES = {
    "58410-2": "CBC",
    "2345-7": "Glucose",
    "34714-6": "INR",
    "10839-9": "Troponin I",
    "164947007": "Normal ECG / Echo",
    "164861001": "Atrial fibrillation on ECG",
    "164873001": "LVH on ECG",
    "164912006": "Ischemic changes on ECG",
    "230692009": "Intracerebral hemorrhage finding",
    "165080006": "No abnormality detected",
    "418052000": "CTA head and neck",
    "204754003": "Patent foramen ovale",
    "400047006": "Left atrial appendage thrombus",
    "63740003": "Bacterial endocarditis",
    "230690007": "Stroke imaging finding",
}


def parse_datetime(value: str) -> datetime:
    value = value.strip()
    if value.endswith("Z"):
        value = value[:-1] + "+00:00"
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def read_csv(path: str) -> Iterable[dict]:
    with open(path, newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            yield row


def load_stroke_events(conditions_path: str) -> Dict[str, dict]:
    stroke_events: Dict[str, dict] = {}
    for row in read_csv(conditions_path):
        code = row["CODE"]
        if code not in STROKE_CONDITION_CODES:
            continue
        patient_id = row["PATIENT"]
        start = parse_datetime(row["START"])
        existing = stroke_events.get(patient_id)
        if existing is None or start < existing["stroke_start"]:
            stroke_events[patient_id] = {
                "patient_id": patient_id,
                "stroke_start": start,
                "stroke_code": code,
                "stroke_type": STROKE_CONDITION_CODES[code],
                "stroke_description": row["DESCRIPTION"],
                "stroke_encounter": row["ENCOUNTER"],
            }
    return stroke_events


def load_encounters(encounters_path: str) -> Dict[str, dict]:
    encounters: Dict[str, dict] = {}
    for row in read_csv(encounters_path):
        encounters[row["Id"]] = row
    return encounters


def timepoint_for_score(
    score_name: str,
    event_time: datetime,
    observation_time: datetime,
    previous_score_times: List[datetime],
) -> str:
    if score_name == "NIHSS":
        if not previous_score_times:
            return "admission"
        return "followup"

    if observation_time <= event_time:
        return "baseline"

    days_since_event = (observation_time - event_time).days
    if days_since_event < 135:
        return "3_month"
    return "6_month"


def extract_post_stroke_observations(
    observations_path: str,
    stroke_events: Dict[str, dict],
    encounters: Dict[str, dict],
) -> Tuple[List[dict], List[dict]]:
    extracted_rows: List[dict] = []
    score_summary: Dict[str, dict] = {}
    score_times: Dict[Tuple[str, str], List[datetime]] = defaultdict(list)

    allowed_codes = set(SCORE_CODES) | set(INVESTIGATION_CODES)

    for row in read_csv(observations_path):
        patient_id = row["PATIENT"]
        event = stroke_events.get(patient_id)
        if event is None:
            continue

        code = row["CODE"]
        if code not in allowed_codes:
            continue

        observation_time = parse_datetime(row["DATE"])
        score_name = SCORE_CODES.get(code)
        if observation_time < event["stroke_start"] and score_name != "mRS":
            continue

        encounter = encounters.get(row["ENCOUNTER"], {})
        label = SCORE_CODES.get(code) or INVESTIGATION_CODES.get(code) or row["DESCRIPTION"]
        summary = score_summary.setdefault(
            patient_id,
            {
                "patient_id": patient_id,
                "stroke_start": event["stroke_start"].isoformat(),
                "stroke_type": event["stroke_type"],
                "stroke_code": event["stroke_code"],
                "stroke_description": event["stroke_description"],
                "baseline_mrs": "",
                "nihss_admission": "",
                "nihss_followup": "",
                "mrs_3_month": "",
                "mrs_6_month": "",
            },
        )
        score_timepoint = ""
        if score_name is not None:
            key = (patient_id, score_name)
            score_timepoint = timepoint_for_score(
                score_name,
                event["stroke_start"],
                observation_time,
                score_times[key],
            )
            score_times[key].append(observation_time)

            if score_name == "NIHSS":
                if score_timepoint == "admission" and not summary["nihss_admission"]:
                    summary["nihss_admission"] = row["VALUE"]
                elif not summary["nihss_followup"]:
                    summary["nihss_followup"] = row["VALUE"]
            elif score_name == "mRS":
                if score_timepoint == "baseline" and not summary["baseline_mrs"]:
                    summary["baseline_mrs"] = row["VALUE"]
                elif score_timepoint == "3_month" and not summary["mrs_3_month"]:
                    summary["mrs_3_month"] = row["VALUE"]
                elif score_timepoint == "6_month" and not summary["mrs_6_month"]:
                    summary["mrs_6_month"] = row["VALUE"]

        extracted_rows.append(
            {
                "patient_id": patient_id,
                "stroke_start": event["stroke_start"].isoformat(),
                "stroke_type": event["stroke_type"],
                "stroke_description": event["stroke_description"],
                "observation_date": row["DATE"],
                "encounter_id": row["ENCOUNTER"],
                "encounter_class": encounter.get("ENCOUNTERCLASS", ""),
                "code": code,
                "description": row["DESCRIPTION"],
                "label": label,
                "category": row["CATEGORY"],
                "value": row["VALUE"],
                "units": row["UNITS"],
                "type": row["TYPE"],
                "score_timepoint": score_timepoint,
            }
        )

    return extracted_rows, list(score_summary.values())


def write_csv(path: str, rows: List[dict]) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if not rows:
        with open(path, "w", newline="", encoding="utf-8") as handle:
            handle.write("")
        return

    fieldnames = list(rows[0].keys())
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description="Extract stroke-related rows from Synthea CSV output.")
    parser.add_argument("--input-dir", default="output/csv", help="Directory containing Synthea CSV output")
    parser.add_argument(
        "--output-dir",
        default="output/stroke_extract",
        help="Directory where stroke-specific CSV files will be written",
    )
    args = parser.parse_args()

    conditions_path = os.path.join(args.input_dir, "conditions.csv")
    observations_path = os.path.join(args.input_dir, "observations.csv")
    encounters_path = os.path.join(args.input_dir, "encounters.csv")

    stroke_events = load_stroke_events(conditions_path)
    encounters = load_encounters(encounters_path)
    extracted_rows, summary_rows = extract_post_stroke_observations(
        observations_path, stroke_events, encounters
    )

    stroke_event_rows = [
        {
            "patient_id": event["patient_id"],
            "stroke_start": event["stroke_start"].isoformat(),
            "stroke_code": event["stroke_code"],
            "stroke_type": event["stroke_type"],
            "stroke_description": event["stroke_description"],
            "stroke_encounter": event["stroke_encounter"],
        }
        for event in sorted(stroke_events.values(), key=lambda item: (item["patient_id"], item["stroke_start"]))
    ]

    extracted_rows.sort(key=lambda row: (row["patient_id"], row["observation_date"], row["code"]))
    summary_rows.sort(key=lambda row: row["patient_id"])

    write_csv(os.path.join(args.output_dir, "stroke_events.csv"), stroke_event_rows)
    write_csv(os.path.join(args.output_dir, "stroke_observations_post_event.csv"), extracted_rows)
    write_csv(os.path.join(args.output_dir, "stroke_score_summary.csv"), summary_rows)

    print(f"Found {len(stroke_event_rows)} patients with stroke events.")
    print(f"Wrote {len(extracted_rows)} post-stroke observation rows.")
    print(f"Wrote summary file to {os.path.join(args.output_dir, 'stroke_score_summary.csv')}.")


if __name__ == "__main__":
    main()
