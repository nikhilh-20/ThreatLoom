"""Thread-safe LLM cost tracking with per-model pricing."""

import threading

# Pricing per 1M tokens: (input, cache_read_input, output)
# Anthropic cache writes: billed at 1.25× input (5-min TTL) or 2× input (1-hour TTL).
# OpenAI cache writes: free (automatic); only cache reads are discounted.
_PRICING = {
    # OpenAI
    "gpt-5-mini":    (0.25, 0.025,  2.00),
    "gpt-4.1-mini":  (0.40, 0.10,   1.60),
    # Anthropic — Claude 4.x
    "claude-haiku":  (1.00, 0.10,   5.00),   # Haiku 4.5
    "claude-sonnet": (3.00, 0.30,  15.00),   # Sonnet 4.6
    "claude-opus":   (5.00, 0.50,  25.00),   # Opus 4.6
}


def _lookup_pricing(model):
    """Return (input_price, cache_read_price, output_price) per 1M tokens for a model."""
    m = model.lower()
    # Sort by key length descending so the most-specific key wins.
    for key, prices in sorted(_PRICING.items(), key=lambda x: -len(x[0])):
        if key in m:
            return prices
    return (1.00, 0.10, 3.00)  # conservative fallback


class CostTracker:
    """Accumulates token usage across a pipeline session.

    Token categories tracked separately:
        input_tokens           — non-cached input tokens (billed at input_price)
        cache_creation_tokens  — tokens written to cache on Anthropic
                                 (billed at 1.25× input for 5-min TTL;
                                  OpenAI caching is free so this is always 0 there)
        cache_read_tokens      — tokens read from cache (billed at cache_read_price,
                                 ~0.1× input for both providers)
        output_tokens          — generated output tokens
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._input_tokens = 0
        self._cache_creation_tokens = 0
        self._cache_read_tokens = 0
        self._output_tokens = 0

    def add_tokens(self, input_tokens, output_tokens,
                   cache_creation_tokens=0, cache_read_tokens=0):
        with self._lock:
            self._input_tokens += input_tokens
            self._cache_creation_tokens += cache_creation_tokens
            self._cache_read_tokens += cache_read_tokens
            self._output_tokens += output_tokens

    def reset(self):
        with self._lock:
            self._input_tokens = 0
            self._cache_creation_tokens = 0
            self._cache_read_tokens = 0
            self._output_tokens = 0

    def get_session_cost(self, model):
        inp_price, cache_read_price, out_price = _lookup_pricing(model)
        cache_write_price = inp_price * 1.25  # conservative: 5-min TTL multiplier
        with self._lock:
            return (
                self._input_tokens            * inp_price
                + self._cache_creation_tokens * cache_write_price
                + self._cache_read_tokens     * cache_read_price
                + self._output_tokens         * out_price
            ) / 1_000_000

    def get_tokens(self):
        """Return (input, cache_creation, cache_read, output) token counts."""
        with self._lock:
            return (
                self._input_tokens,
                self._cache_creation_tokens,
                self._cache_read_tokens,
                self._output_tokens,
            )

    @staticmethod
    def estimate_summarization_cost(article_count, model):
        """Estimate cost for summarizing a batch of articles with prompt caching.

        The expanded SUMMARY_PROMPT (~4,500 tokens) is written to cache on the
        first article and read from cache on all subsequent articles in the batch.
        """
        if article_count == 0:
            return 0.0
        inp_price, cache_read_price, out_price = _lookup_pricing(model)
        cache_write_price = inp_price * 1.25
        system_tokens = 4500   # expanded SUMMARY_PROMPT with MITRE reference
        user_tokens   = 3000   # typical article content
        out_tokens    = 500    # typical summary output

        first = (system_tokens * cache_write_price
                 + user_tokens * inp_price
                 + out_tokens  * out_price) / 1_000_000
        rest  = max(0, article_count - 1) * (
                 system_tokens * cache_read_price
                 + user_tokens * inp_price
                 + out_tokens  * out_price) / 1_000_000
        return (first + rest) * 1.5  # 1.5× buffer for variance


# Singleton instance used by the whole application
cost_tracker = CostTracker()
