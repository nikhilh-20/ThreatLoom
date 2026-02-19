"""Thread-safe LLM cost tracking with per-model pricing."""

import threading

# Pricing per 1M tokens: (input, output)
_PRICING = {
    "gpt-4o-mini": (0.15, 0.60),
    "gpt-4o": (2.50, 10.00),
    "gpt-4-turbo": (10.00, 30.00),
    "gpt-3.5-turbo": (0.50, 1.50),
    "claude-3-5-haiku": (0.80, 4.00),
    "claude-sonnet": (3.00, 15.00),
    "claude-opus": (15.00, 75.00),
}


def _lookup_pricing(model):
    """Return (input_price, output_price) per 1M tokens for a model."""
    m = model.lower()
    for key, prices in _PRICING.items():
        if key in m:
            return prices
    return (1.00, 3.00)  # conservative fallback


class CostTracker:
    """Accumulates token usage across a pipeline session."""

    def __init__(self):
        self._lock = threading.Lock()
        self._input_tokens = 0
        self._output_tokens = 0

    def add_tokens(self, input_tokens, output_tokens):
        with self._lock:
            self._input_tokens += input_tokens
            self._output_tokens += output_tokens

    def reset(self):
        with self._lock:
            self._input_tokens = 0
            self._output_tokens = 0

    def get_session_cost(self, model):
        inp_price, out_price = _lookup_pricing(model)
        with self._lock:
            return (self._input_tokens * inp_price + self._output_tokens * out_price) / 1_000_000

    def get_tokens(self):
        with self._lock:
            return self._input_tokens, self._output_tokens

    @staticmethod
    def estimate_summarization_cost(article_count, model):
        """Estimate cost for summarizing articles. Doubled for anchoring."""
        inp_price, out_price = _lookup_pricing(model)
        raw = article_count * (4000.0 * inp_price + 500.0 * out_price) / 1_000_000
        return raw * 2.0


# Singleton instance used by the whole application
cost_tracker = CostTracker()
