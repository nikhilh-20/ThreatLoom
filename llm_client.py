"""Provider-aware LLM client abstraction supporting OpenAI and Anthropic."""

import logging

from config import load_config

logger = logging.getLogger(__name__)


def has_api_key():
    """Return True if the configured LLM provider has an API key set."""
    config = load_config()
    provider = config.get("llm_provider", "openai")
    if provider == "anthropic":
        return bool(config.get("anthropic_api_key", "").strip())
    return bool(config.get("openai_api_key", "").strip())


def get_model_name():
    """Return the active model name for the configured provider."""
    config = load_config()
    provider = config.get("llm_provider", "openai")
    if provider == "anthropic":
        return config.get("anthropic_model", "claude-haiku-4-5-20251001")
    return config.get("openai_model", "gpt-5.4-nano")


def call_llm(system_prompt, messages, temperature=0.3, max_tokens=2000,
             json_mode=False, system_blocks=None):
    """Make an LLM API call using the configured provider.

    Args:
        system_prompt: System instruction string, or None.
        messages: List of {"role": ..., "content": ...} dicts (user/assistant).
        temperature: Sampling temperature.
        max_tokens: Maximum output tokens.
        json_mode: If True, instruct the model to respond with valid JSON only.
        system_blocks: Optional list of pre-structured Anthropic content blocks
            for the system field (Anthropic only). When provided, ``system_prompt``
            is ignored for Anthropic calls. For OpenAI, the text of each block is
            joined and used as the system message. Each block must follow the form:
            {"type": "text", "text": "...", "cache_control": {...}}

    Returns:
        5-tuple of (content_string, input_tokens, output_tokens,
                    cache_creation_tokens, cache_read_tokens).
        cache_creation_tokens: tokens written to Anthropic cache (0 for OpenAI).
        cache_read_tokens: tokens served from cache (both providers).

    Raises:
        Exception on API errors — caller handles retries.
    """
    config = load_config()
    provider = config.get("llm_provider", "openai")
    if provider == "anthropic":
        return _call_anthropic(system_prompt, messages, temperature, max_tokens,
                               json_mode, config, system_blocks=system_blocks)
    return _call_openai(system_prompt, messages, temperature, max_tokens,
                        json_mode, config, system_blocks=system_blocks)


def _call_openai(system_prompt, messages, temperature, max_tokens, json_mode,
                 config, system_blocks=None):
    from openai import OpenAI

    api_key = config.get("openai_api_key", "").strip()
    model = config.get("openai_model", "gpt-5.4-nano")
    client = OpenAI(api_key=api_key)

    # Resolve system text: prefer system_blocks (join text fields), else system_prompt.
    if system_blocks:
        resolved_system = "\n\n".join(
            b["text"] for b in system_blocks if b.get("type") == "text"
        )
    else:
        resolved_system = system_prompt

    all_messages = []
    if resolved_system:
        all_messages.append({"role": "system", "content": resolved_system})
    all_messages.extend(messages)

    # Newer OpenAI reasoning models (o-series, gpt-5*) require
    # max_completion_tokens, don't support temperature, and use
    # internal reasoning tokens that count toward the limit.
    _reasoning = model.startswith(("o1", "o3", "o4", "gpt-5"))
    if _reasoning:
        # Pad for reasoning overhead (~3x the desired output tokens)
        kwargs = {
            "model": model,
            "messages": all_messages,
            "max_completion_tokens": max_tokens * 3,
        }
    else:
        kwargs = {
            "model": model,
            "messages": all_messages,
            "max_tokens": max_tokens,
            "temperature": temperature,
        }
        if json_mode:
            kwargs["response_format"] = {"type": "json_object"}

    logger.debug("OpenAI request: model=%s params=%s", model, list(kwargs.keys()))
    resp = client.chat.completions.create(**kwargs)
    content = resp.choices[0].message.content
    prompt_tokens = resp.usage.prompt_tokens if resp.usage else 0
    output_tokens = resp.usage.completion_tokens if resp.usage else 0

    # OpenAI caching is automatic; extract cached token count from usage.
    cached_tokens = 0
    if resp.usage:
        details = getattr(resp.usage, "prompt_tokens_details", None)
        if details:
            cached_tokens = getattr(details, "cached_tokens", 0) or 0

    input_tokens = prompt_tokens - cached_tokens  # non-cached portion
    # OpenAI does not charge for cache writes, so cache_creation_tokens = 0.
    return content, input_tokens, output_tokens, 0, cached_tokens


def _call_anthropic(system_prompt, messages, temperature, max_tokens, json_mode,
                    config, system_blocks=None):
    import time
    import anthropic

    api_key = config.get("anthropic_api_key", "").strip()
    model = config.get("anthropic_model", "claude-haiku-4-5-20251001")
    client = anthropic.Anthropic(api_key=api_key)

    # Separate system-role messages from user/assistant messages.
    system_parts = []
    if system_prompt:
        system_parts.append(system_prompt)
    non_system = []
    for msg in messages:
        if msg["role"] == "system":
            system_parts.append(msg["content"])
        else:
            non_system.append({"role": msg["role"], "content": msg["content"]})

    final_system = "\n\n".join(system_parts) if system_parts else None
    if json_mode:
        json_instr = "IMPORTANT: You must respond with valid JSON only. No text before or after the JSON."
        final_system = f"{final_system}\n\n{json_instr}" if final_system else json_instr

    # Anthropic requires at least one user message.
    if not non_system:
        non_system.append({"role": "user", "content": "Please proceed."})

    # Anthropic requires alternating roles; merge consecutive same-role messages.
    merged = _merge_consecutive(non_system)

    kwargs = {
        "model": model,
        "max_tokens": max_tokens,
        "messages": merged,
        "temperature": temperature,
    }

    # Build the system field.
    # Callers may pass pre-structured content blocks (e.g. to split a large system
    # prompt into a stable cached block and a dynamic per-request block).
    # Otherwise, wrap the resolved system string in a single block marked for caching.
    if system_blocks is not None:
        # json_mode instruction must be appended to the last block's text if present.
        if json_mode and system_blocks:
            last = system_blocks[-1]
            system_blocks = system_blocks[:-1] + [{
                **last,
                "text": last["text"] + "\n\nIMPORTANT: You must respond with valid JSON only. No text before or after the JSON.",
            }]
        kwargs["system"] = system_blocks
    elif final_system:
        kwargs["system"] = [
            {
                "type": "text",
                "text": final_system,
                "cache_control": {"type": "ephemeral"},
            }
        ]

    retry_delay = 10  # seconds; doubles each attempt, capped at 120 s
    for attempt in range(4):
        try:
            resp = client.messages.create(**kwargs)
            content = next((b.text for b in resp.content if b.type == "text"), "")
            if json_mode and content:
                content = _strip_code_fences(content)

            input_tokens     = resp.usage.input_tokens if resp.usage else 0
            output_tokens    = resp.usage.output_tokens if resp.usage else 0
            cache_creation   = getattr(resp.usage, "cache_creation_input_tokens", 0) or 0
            cache_read       = getattr(resp.usage, "cache_read_input_tokens", 0) or 0

            return content, input_tokens, output_tokens, cache_creation, cache_read

        except anthropic.RateLimitError as e:
            wait = retry_delay
            try:
                ra = getattr(getattr(e, "response", None), "headers", {}).get("retry-after")
                if ra:
                    wait = max(int(ra), retry_delay)
            except Exception:
                pass
            logger.warning(
                f"Anthropic rate limited, waiting {wait}s before retry (attempt {attempt + 1})"
            )
            time.sleep(wait)
            retry_delay = min(retry_delay * 2, 120)
        except anthropic.APIError as e:
            if attempt < 3:
                logger.warning(f"Anthropic API error (attempt {attempt + 1}): {e}, retrying...")
                time.sleep(2)
            else:
                raise

    raise RuntimeError("Anthropic API: all retries failed (rate limit)")


def _strip_code_fences(text):
    """Strip markdown code fences from text.

    Claude occasionally wraps JSON responses in ```json...``` blocks even when
    instructed not to. This removes the fences so json.loads() can parse the content.
    """
    text = text.strip()
    if text.startswith("```"):
        newline = text.find("\n")
        text = text[newline + 1:] if newline != -1 else text[3:]
        if text.rstrip().endswith("```"):
            text = text.rstrip()[:-3].rstrip()
    return text


def _merge_consecutive(messages):
    """Merge consecutive same-role messages; ensure first message is 'user'."""
    if not messages:
        return messages
    result = []
    for msg in messages:
        if result and result[-1]["role"] == msg["role"]:
            result[-1] = {
                "role": msg["role"],
                "content": result[-1]["content"] + "\n\n" + msg["content"],
            }
        else:
            result.append({"role": msg["role"], "content": msg["content"]})
    if result[0]["role"] != "user":
        result.insert(0, {"role": "user", "content": "Please proceed with the following context."})
    return result
