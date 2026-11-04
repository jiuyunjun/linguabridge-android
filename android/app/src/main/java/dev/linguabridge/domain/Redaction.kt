package dev.linguabridge.domain

/** Export can only receive replacements; original text never serves as a fallback. */
fun UiSnapshot.redacted(replacements: Map<String, String>): UiSnapshot = copy(
    nodes = nodes.map { node -> node.copy(
        text = node.text?.let { replacements["${node.path}:text"]?.take(1000) ?: "[REDACTED]" },
        description = node.description?.let { replacements["${node.path}:description"]?.take(1000) ?: "[REDACTED]" },
    ) },
)
