# AI

Koog-powered AI assistant with 32 SimpleTool implementations. All tools use repository ports — no direct Room access. Platform split: JVM uses `MultiLLMPromptExecutor`, Android uses `AndroidKoogFactory`.

## Structure

```
ai/
├── KoogAgentService.kt   Agent orchestration, tool loop
├── LlmProvider.kt        Model selection, provider config
├── TextGenPort.kt       LLM generation interface
├── tools/               32 SimpleTool implementations
│   ├── AdrTools.kt       write_adr, list_adrs, read_adr
│   ├── CreateTaskTool, DeleteTaskTool, GetTaskTool, ListTasksTool, UpdateTaskTool, SearchTasksTool
│   ├── CreateNoteTool, UpdateNoteTool, GetNoteTool, DeleteNoteTool
│   ├── CreateProjectTool, UpdateProjectTool, GetProjectTool, DeleteProjectTool, ListProjectsTool
│   ├── CreateTagTool, DeleteTagTool
│   ├── ListLinkedTasksTool
│   ├── RefineTaskTool, SmartRewriteTool, GenerateDescriptionTool, GenerateChecklistTool
│   ├── DecomposeTaskTool, DecomposeAndCreateTool
│   ├── ImproveNoteTool
│   ├── PickTimeTool, ClusterTasksTool, ClusterNotesTool
│   ├── ProjectReviewTool, WeeklyPlanTool
│   └── ToolFactories.kt  llmTool<I,O>(), dataTool<I,O>() inline factories
├── prompts/              System + user prompt templates
├── chat/                 ChatScreen + ChatViewModel
├── use_cases/            RefineTaskUseCase, DecomposeTaskUseCase, etc.
├── data/                 AiSettingsRepository, UsageRecorder
└── di/                  AiToolsDiModule.kt → AiToolsModule.kt
```

## Key entry points

| What | Where |
|---|---|
| Tool registration | `AiToolsModule.kt` (was `AiToolsDiModule.kt`) |
| Agent service | `KoogAgentService.kt` |
| Chat UI | `ChatScreen` + `ChatViewModel` |
| Tool factories | `llmTool<I,O>()`, `dataTool<I,O>()` in `ToolFactories.kt` |

## AI → domain boundary

Tools receive parsed input (data class), call repository suspend functions, return JSON. No direct database access in `tools/`. LLM prompts live in `prompts/`.

## Relevant ADRs

- `docs/decisions/2026-09-05-koog-both-platforms.md` — why platform split exists
- `docs/decisions/2026-09-05-llm-provider-settings.md` — provider config
- `docs/decisions/2026-09-05-koog-test-workarounds.md` — testing strategy
