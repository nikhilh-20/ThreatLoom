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
        return config.get("anthropic_model", "claude-3-5-haiku-20241022")
    return config.get("openai_model", "gpt-4o-mini")


def call_llm(system_prompt, messages, temperature=0.3, max_tokens=2000, json_mode=False):
    """Make an LLM API call using the configured provider.

    Args:
        system_prompt: System instruction string, or None.
        messages: List of {"role": ..., "content": ...} dicts (user/assistant).
        temperature: Sampling temperature.
        max_tokens: Maximum output tokens.
        json_mode: If True, instruct the model to respond with valid JSON only.

    Returns:
        Tuple of (content_string, input_tokens, output_tokens).

    Raises:
        Exception on API errors — caller handles retries.
    """
    config = load_config()
    provider = config.get("llm_provider", "openai")
    if provider == "anthropic":
        return _call_anthropic(system_prompt, messages, temperature, max_tokens, json_mode, config)
    return _call_openai(system_prompt, messages, temperature, max_tokens, json_mode, config)


def _call_openai(system_prompt, messages, temperature, max_tokens, json_mode, config):
    from openai import OpenAI

    api_key = config.get("openai_api_key", "").strip()
    model = config.get("openai_model", "gpt-4o-mini")
    client = OpenAI(api_key=api_key)

    all_messages = []
    if system_prompt:
        all_messages.append({"role": "system", "content": system_prompt})
    all_messages.extend(messages)

    kwargs = {
        "model": model,
        "messages": all_messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
    }
    if json_mode:
        kwargs["response_format"] = {"type": "json_object"}

    resp = client.chat.completions.create(**kwargs)
    content = resp.choices[0].message.content
    input_tokens = resp.usage.prompt_tokens if resp.usage else 0
    output_tokens = resp.usage.completion_tokens if resp.usage else 0
    return content, input_tokens, output_tokens


def _call_anthropic(system_prompt, messages, temperature, max_tokens, json_mode, config):
    import anthropic

    api_key = config.get("anthropic_api_key", "").strip()
    model = config.get("anthropic_model", "claude-3-5-haiku-20241022")
    client = anthropic.Anthropic(api_key=api_key)

    # Separate system-role messages from user/assistant messages
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

    # Anthropic requires at least one user message
    if not non_system:
        non_system.append({"role": "user", "content": "Please proceed."})

    # Anthropic requires alternating roles; merge consecutive same-role messages
    merged = _merge_consecutive(non_system)

    kwargs = {
        "model": model,
        "max_tokens": max_tokens,
        "messages": merged,
        "temperature": temperature,
    }
    if final_system:
        kwargs["system"] = final_system

    resp = client.messages.create(**kwargs)
    content = next((b.text for b in resp.content if b.type == "text"), "")
    input_tokens = resp.usage.input_tokens if resp.usage else 0
    output_tokens = resp.usage.output_tokens if resp.usage else 0
    return content, input_tokens, output_tokens


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
