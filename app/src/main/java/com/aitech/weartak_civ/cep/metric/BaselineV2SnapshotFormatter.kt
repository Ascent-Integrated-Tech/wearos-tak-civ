package com.aitech.weartak_civ.cep.metric

/** Stable, human-readable export for the display-only V2 diagnostics surface. */
object BaselineV2SnapshotFormatter {
    fun format(batch: BaselineV2SnapshotBatch?): String = buildString {
        appendLine("baseline_v2_snapshot")
        if (batch == null) {
            appendLine("state=EMPTY")
            return@buildString
        }
        appendLine("generation=${batch.generation}")
        appendLine("published_at_ms=${batch.publishedAtMs}")
        batch.snapshots.values.sortedBy { it.model.metricId }.forEach { snapshot ->
            val model = snapshot.model
            appendLine("[metric=${model.metricId}]")
            appendLine("observed_value=${snapshot.observation.observedValue}")
            appendLine("expected_value=${model.expectedValue}")
            appendLine("lower_expected_bound=${model.lowerExpectedBound}")
            appendLine("upper_expected_bound=${model.upperExpectedBound}")
            appendLine("lifecycle=${model.lifecycle}")
            appendLine("confidence=${model.confidence.aggregate}")
            appendLine("sample_count=${model.sampleCount}")
            appendLine("covered_seconds=${model.coveredSeconds}")
            appendLine("active_days=${model.activeDays}")
            appendLine("quality=${snapshot.observation.quality.state}")
            appendLine("quality_score=${snapshot.observation.quality.score}")
            appendLine("model_version=${snapshot.modelVersion}")
            appendLine("definition_version=${model.definition.version}")
            appendLine("threshold_formula_version=${snapshot.thresholdFormulaVersion}")
        }
    }
}
