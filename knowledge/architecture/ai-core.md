---
type: Architecture
title: AI Core
description: Tool call loop and tool output.

canonical_for:
- K-aicore-001

sources:
- id: tool-call-loop
  resource: 
    repo:/shared/src/commonMain/kotlin/com/singularity/todo/core/ai/ToolCallLoop.kt
generated:
- by: agent
  source_state:
    tool-call-loop: 5a263337b8aec7ec0a691674326f6a0a84a76918
  body_state: 35dd8dbf5e49fcd18d2cdec8eda25f15933b52b63a1508033d5ed0c63c8b05e9
verified:
- by: human:maintainer
  source_state:
    tool-call-loop: 5a263337b8aec7ec0a691674326f6a0a84a76918
  body_state: 35dd8dbf5e49fcd18d2cdec8eda25f15933b52b63a1508033d5ed0c63c8b05e9
---

# AI Core

ToolCallLoop runs the AI tool call loop.
