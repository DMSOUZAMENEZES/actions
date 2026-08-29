# Architecture

The runtime separates intent recognition from Android execution.

```text
User -> IntentModel -> ToolRegistry -> PolicyEngine -> ActionDispatcher
                                          |               |
                                          |               +-> NativeActionExecutor
                                          |               +-> AccessibilityActionExecutor
                                          +-> ConfirmationManager
```

Core concepts:

- **IntentModel**: abstraction over FunctionGemma/LiteRT-LM or another local/remote model.
- **ToolRegistry**: exposes atomic tools to the model and maps tool calls to typed actions.
- **PolicyEngine**: classifies actions by risk and decides whether confirmation is required.
- **ActionDispatcher**: executes typed actions and returns a real execution result.
- **Accessibility layer**: reserved for UI automation when no stable Android API/Intent exists.
- **Skills**: multi-step flows composed from atomic tools.

The first milestone focuses on native Android actions and the policy/runtime contracts. Accessibility and multi-step skills come next.
