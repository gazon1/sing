package com.singularity.todo.mcp

/** The flags that decide the exposed toolbox, and where they came from. */
data class ToolConfiguration(
    val flags: Map<String, Boolean>,
    val status: ConfigurationStatus,
    val fetchedAtEpochMillis: Long?,
)
