# Android Function Runtime

Experimental on-device Android action runtime built around Google AI Edge LiteRT-LM and FunctionGemma-style tool calling.

The project is developed as an independent modular runtime rather than as a direct fork of the Google AI Edge Gallery Mobile Actions demo.

## Current architecture

```text
User command
   -> LiteRtFunctionGemmaIntentModel
   -> FunctionGemma tool call (manual execution mode)
   -> ToolRegistry
   -> AndroidAction
   -> PolicyEngine
   -> ActionDispatcher
   -> Android API / Intent
```

LiteRT-LM automatic tool execution is disabled. The language model is responsible only for function selection and argument extraction. Android execution occurs after the runtime has converted the tool call into a typed action and evaluated its risk policy.

## Implemented tools

- `open_app` — opens a launchable app by label or package name.
- `open_wifi_settings` — opens Android Wi-Fi settings.
- `open_url` — opens an absolute URL.
- `dial_number` — opens the dialer and requires user confirmation.

## Demo app

The `app` module provides a Jetpack Compose test UI. It can import a `.litertlm` model through the Android document picker, copy it into app-private storage, run an on-device command, display the selected action and request confirmation when required.

Recommended model for the current milestone:

- Repository: `litert-community/functiongemma-270m-ft-mobile-actions`
- File: `mobile_actions_q8_ekv1024.litertlm`
- Runtime: CPU
- Sampling: topK 64, topP 0.95, temperature 0.0

The model file is intentionally not committed to this repository.

## Safety boundary

Actions are classified as `SAFE`, `SENSITIVE`, or `DESTRUCTIVE`. Sensitive and destructive actions require explicit confirmation before execution. Planning and execution are separate, so confirmation does not require a second LLM inference.

## Next milestones

1. Expand native Android tools: calendar, contacts, alarms, sharing, media and device state.
2. Add structured parameter metadata to the dynamic ToolRegistry.
3. Add AccessibilityService and semantic UI-tree inspection.
4. Add multi-step Skills such as YouTube search and WhatsApp message preparation.
5. Add model download/verification and compatibility checks.
