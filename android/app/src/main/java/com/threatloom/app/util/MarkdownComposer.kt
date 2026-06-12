package com.threatloom.app.util

import com.threatloom.app.data.remote.dto.SummaryResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarkdownComposer @Inject constructor() {

    fun compose(data: SummaryResult): String {
        val sections = mutableListOf<String>()

        sections.add("# Executive Summary")
        sections.add(data.executiveSummary.ifBlank { "No summary available." })
        sections.add("")

        sections.add("# Details")
        if (data.details.isNotEmpty()) {
            data.details.forEach { sections.add("- $it") }
        } else {
            sections.add("No details available.")
        }
        sections.add("")

        if (data.analystNotes.isNotBlank()) {
            sections.add("# Analyst Notes")
            sections.add(data.analystNotes)
            sections.add("")
        }

        sections.add("# Mitigations")
        if (data.mitigations.isNotEmpty()) {
            data.mitigations.forEach { sections.add("- $it") }
        } else {
            sections.add("No mitigations listed.")
        }

        if (data.iocs.isNotEmpty()) {
            sections.add("")
            sections.add("# IOCs")
            data.iocs.forEach { sections.add("- $it") }
        }

        return sections.joinToString("\n")
    }
}
