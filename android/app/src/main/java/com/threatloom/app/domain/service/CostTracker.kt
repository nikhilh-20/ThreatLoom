package com.threatloom.app.domain.service

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class CostSnapshot(
    val input: Int = 0,
    val output: Int = 0,
    val cacheWrite: Int = 0,
    val cacheRead: Int = 0
)

@Singleton
class CostTracker @Inject constructor() {

    private val inputTokens = AtomicInteger(0)
    private val outputTokens = AtomicInteger(0)
    private val cacheWriteTokens = AtomicInteger(0)
    private val cacheReadTokens = AtomicInteger(0)

    fun addTokens(input: Int, output: Int) {
        inputTokens.addAndGet(input)
        outputTokens.addAndGet(output)
    }

    fun addUsage(input: Int, output: Int, cacheWrite: Int = 0, cacheRead: Int = 0) {
        inputTokens.addAndGet(input)
        outputTokens.addAndGet(output)
        cacheWriteTokens.addAndGet(cacheWrite)
        cacheReadTokens.addAndGet(cacheRead)
    }

    fun reset() {
        inputTokens.set(0)
        outputTokens.set(0)
        cacheWriteTokens.set(0)
        cacheReadTokens.set(0)
    }

    fun getSnapshot(): CostSnapshot = CostSnapshot(
        inputTokens.get(), outputTokens.get(),
        cacheWriteTokens.get(), cacheReadTokens.get()
    )

    fun getTokens(): Pair<Int, Int> = inputTokens.get() to outputTokens.get()

    fun getSessionCost(model: String): Double {
        val (inputPrice, cacheReadPrice, outputPrice) = pricingPer1M(model)
        val cacheWritePrice = cacheWritePrice(model, inputPrice)
        return (inputTokens.get() * inputPrice
                + outputTokens.get() * outputPrice
                + cacheWriteTokens.get() * cacheWritePrice
                + cacheReadTokens.get() * cacheReadPrice) / 1_000_000.0
    }

    fun estimateSummarizationCost(articleCount: Int, model: String): Double {
        if (articleCount <= 0) return 0.0
        val (inputPrice, cacheReadPrice, outputPrice) = pricingPer1M(model)
        // Conservative write rate for the first-article system prompt (matches the web
        // estimate_summarization_cost; kept provider-agnostic so both apps agree).
        val cacheWritePrice = inputPrice * 1.25
        // The large summary system prompt is written to cache on the first article
        // and read from cache on every subsequent article in the batch.
        val systemTokens = 4500.0   // expanded SUMMARY_PROMPT with extraction example
        val userTokens = 3000.0     // typical article content
        val outTokens = 500.0       // typical summary output
        val first = (systemTokens * cacheWritePrice
                + userTokens * inputPrice
                + outTokens * outputPrice) / 1_000_000.0
        val rest = maxOf(0, articleCount - 1) * (systemTokens * cacheReadPrice
                + userTokens * inputPrice
                + outTokens * outputPrice) / 1_000_000.0
        return (first + rest) * 1.5  // 1.5x buffer for variance
    }

    fun estimateTrendCost(
        articles: List<com.threatloom.app.domain.model.ArticleWithSummary>,
        model: String
    ): Triple<Double, Int, Int> {
        val groups = articles.groupBy { a ->
            val parts = (a.publishedDate ?: "").split("-")
            val year = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val month = parts.getOrNull(1)?.toIntOrNull() ?: 0
            year to ((month - 1) / 3 + 1)
        }.filterKeys { (y, _) -> y > 0 }
        val nQ = groups.size
        val nY = groups.keys.map { it.first }.toSet().size
        val nBatches = groups.values.sumOf { maxOf(0, (it.size - 1) / 50) }
        val (inp, _, out) = pricingPer1M(model)
        val cost = ((nQ * 3000 + nY * 8000 + nBatches * 15000) * inp +
                    (nQ * 2500 + nY * 3000 + nBatches * 1500) * out) / 1_000_000.0
        return Triple(cost, nQ, nY)
    }

    fun estimateInsightCost(articleCount: Int, model: String): Double {
        val (inp, _, out) = pricingPer1M(model)
        val estInput = minOf(articleCount * 200, 5000) + 200
        return (estInput * inp + 2000.0 * out) / 1_000_000.0
    }

    fun deltaCost(before: CostSnapshot, after: CostSnapshot, model: String): Double {
        val (inputPrice, cacheReadPrice, outputPrice) = pricingPer1M(model)
        val cacheWritePrice = cacheWritePrice(model, inputPrice)
        return ((after.input - before.input) * inputPrice
                + (after.output - before.output) * outputPrice
                + (after.cacheWrite - before.cacheWrite) * cacheWritePrice
                + (after.cacheRead - before.cacheRead) * cacheReadPrice) / 1_000_000.0
    }

    fun deltaCost(before: Pair<Int, Int>, after: Pair<Int, Int>, model: String): Double {
        val (inp, _, out) = pricingPer1M(model)
        return ((after.first - before.first) * inp + (after.second - before.second) * out) / 1_000_000.0
    }

    /** Anthropic charges 1.25x input for 5-min cache writes; OpenAI cache writes are free. */
    private fun cacheWritePrice(model: String, inputPrice: Double): Double {
        return if ("claude" in model.lowercase()) inputPrice * 1.25 else 0.0
    }

    // Pricing per 1M tokens: (input, cacheRead, output). Only the models offered in settings.
    private fun pricingPer1M(model: String): Triple<Double, Double, Double> {
        val m = model.lowercase()
        return when {
            "gpt-5-mini" in m -> Triple(0.25, 0.025, 2.00)
            "gpt-5.4-nano" in m -> Triple(0.20, 0.020, 1.25)
            "claude-haiku" in m -> Triple(1.00, 0.10, 5.00)
            "claude-sonnet" in m -> Triple(3.00, 0.30, 15.00)
            else -> Triple(1.00, 0.10, 3.00)
        }
    }
}
