package com.singularity.todo.feature.ai

/**
 * Pricing in USD per 1,000,000 tokens (USD * 10^-6 per token).
 * Source: OpenAI / Anthropic / Ollama pricing pages, 2026-09-07.
 * Update this when prices change.
 */
data class ModelPricing(val inputPerMillionUsd: Double, val outputPerMillionUsd: Double) {
    /**
     * Calculates total cost in micros (USD * 10^-6).
     *
     * Example: gpt-4o-mini, 1000 input + 500 output tokens
     * = 1000 * 0.15/1e6 + 500 * 0.60/1e6 = 0.00015 + 0.0003 = 0.00045 USD = 450 micros
     */
    fun priceTokens(inputTokens: Int, outputTokens: Int): Long {
        val inputCost = inputTokens * inputPerMillionUsd / 1_000_000
        val outputCost = outputTokens * outputPerMillionUsd / 1_000_000
        return ((inputCost + outputCost) * 1_000_000).toLong()
    }
}

/** Pricing lookup table — "pricing last updated: 2026-09-07" */
internal object ModelPricingTable {
    val TABLE: Map<String, ModelPricing> = buildMap {
        // OpenAI
        put("gpt-4o-mini", ModelPricing(0.15, 0.60))
        put("gpt-4o", ModelPricing(2.50, 10.00))
        put("gpt-4.1", ModelPricing(2.00, 8.00))
        put("gpt-4.1-mini", ModelPricing(0.50, 2.00))
        put("gpt-4.1-nano", ModelPricing(0.10, 0.40))
        // Anthropic (via OpenAI-compatible endpoint)
        put("claude-sonnet-4-20250514", ModelPricing(3.00, 15.00))
        put("claude-3-5-sonnet-20241022", ModelPricing(3.00, 15.00))
        put("claude-3-5-haiku-20241022", ModelPricing(0.80, 4.00))
        // Ollama (local — free)
        put("llama3", ModelPricing(0.0, 0.0))
        put("mistral", ModelPricing(0.0, 0.0))
        put("qwen2.5", ModelPricing(0.0, 0.0))
    }

    fun priceOrNull(modelId: String, inputTokens: Int, outputTokens: Int): Long? =
        TABLE[modelId]?.priceTokens(inputTokens, outputTokens)
}
