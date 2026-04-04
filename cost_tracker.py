"""Thread-safe LLM cost tracking with per-model pricing."""

import threading

# Pricing per 1M tokens: (input, cached_input, output)
_PRICING = {
    "gpt-5-mini": (0.25, 0.025, 2.00),
    "gpt-4.1-mini": (0.40, 0.10, 1.60),
    "claude-haiku": (0.80, 0.08, 4.00),
    "claude-sonnet": (3.00, 0.30, 15.00),
    "claude-opus": (15.00, 1.50, 75.00),
}


def _lookup_pricing(model):
    """Return (input_price, cached_input_price, output_price) per 1M tokens for a model."""
    m = model.lower()
    for key, prices in _PRICING.items():
        if key in m:
            return prices
    return (1.00, 0.10, 3.00)  # conservative fallback


class CostTracker:
    """Accumulates token usage across a pipeline session."""

    def __init__(self):
        self._lock = threading.Lock()
        self._input_tokens = 0
        self._cached_input_tokens = 0
        self._output_tokens = 0

    def add_tokens(self, input_tokens, output_tokens, cached_input_tokens=0):
        with self._lock:
            self._input_tokens += input_tokens
            self._cached_input_tokens += cached_input_tokens
            self._output_tokens += output_tokens

    def reset(self):
        with self._lock:
            self._input_tokens = 0
            self._cached_input_tokens = 0
            self._output_tokens = 0

    def get_session_cost(self, model):
        inp_price, cached_price, out_price = _lookup_pricing(model)
        with self._lock:
            return (
                self._input_tokens * inp_price
                + self._cached_input_tokens * cached_price
                + self._output_tokens * out_price
            ) / 1_000_000

    def get_tokens(self):
        with self._lock:
            return self._input_tokens, self._cached_input_tokens, self._output_tokens

    @staticmethod
    def estimate_summarization_cost(article_count, model):
        """Estimate cost for summarizing articles. Doubled for anchoring."""
        inp_price, _cached_price, out_price = _lookup_pricing(model)
        raw = article_count * (4000.0 * inp_price + 500.0 * out_price) / 1_000_000
        return raw * 2.0


# Singleton instance used by the whole application
cost_tracker = CostTracker()
