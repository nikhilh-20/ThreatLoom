package com.threatloom.app.domain.usecase

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.remote.dto.*
import com.threatloom.app.domain.service.LlmService
import com.threatloom.app.util.AppLogger
import javax.inject.Inject

class CheckRelevanceUseCase @Inject constructor(
    private val llmService: LlmService,
    private val appLogger: AppLogger
) {
    companion object {
        private const val TAG = "CheckRelevance"
        private const val BATCH_SIZE = 25

        private const val RELEVANCE_PROMPT = """You are a cybersecurity threat-intelligence triage analyst.
Classify each article title as RELEVANT or IRRELEVANT to cybersecurity threat research.

RELEVANT — include if the article is about ANY of these:
  Malware analysis, exploits, vulnerabilities (CVEs), attack campaigns, threat actors/APTs,
  zero-days, supply chain attacks, security advisories, novel attack techniques or tooling,
  breach investigations with technical details, proof-of-concept exploits, offensive/defensive
  security tool releases, botnets, ransomware operations, exploit kits, C2 infrastructure,
  phishing campaigns, firmware/hardware security research.

IRRELEVANT — exclude if the article is ONLY about:
  Business/financial news, regulatory or legal actions, privacy policy changes, fines or lawsuits,
  mergers & acquisitions, product marketing, career/hiring, opinion without technical substance,
  awards, conference announcements, stock prices, executive appointments.

IMPORTANT: When in doubt, classify as RELEVANT.

Titles:
{titles}

Respond with a JSON object: {"relevant": [true, false, ...]} — one boolean per title, same order."""
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val relevanceAdapter = moshi.adapter(RelevanceResult::class.java)

    suspend operator fun invoke(titles: List<String>): List<Boolean> {
        if (titles.isEmpty()) return emptyList()

        if (!llmService.hasApiKey()) return List(titles.size) { true }

        val results = mutableListOf<Boolean>()

        for (i in titles.indices step BATCH_SIZE) {
            val batch = titles.subList(i, minOf(i + BATCH_SIZE, titles.size))
            val numbered = batch.mapIndexed { j, t -> "${j + 1}. \"$t\"" }.joinToString("\n")
            val prompt = RELEVANCE_PROMPT.replace("{titles}", numbered)

            try {
                val resultJson = llmService.chatCompletion(
                    messages = listOf(ChatMessageDto("user", prompt)),
                    temperature = 0f,
                    maxTokens = 300,
                    jsonMode = true
                ).content

                val data = relevanceAdapter.fromJson(resultJson)
                val batchResults = data?.relevant ?: List(batch.size) { true }
                results.addAll(batchResults.take(batch.size))
                // Pad if fewer results returned
                while (results.size < i + batch.size) results.add(true)
            } catch (e: Exception) {
                appLogger.e(TAG, "Relevance check failed: ${e.message}")
                results.addAll(List(batch.size) { true })
            }
        }

        return results
    }
}
