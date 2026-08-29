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

The runtime currently supports the complete function set used by Google's MobileActions-270M demo plus three runtime extensions:

- `flashlight_on` / `flashlight_off` — controls the torch with explicit Android CAMERA permission handling.
- `create_contact` — opens Android contact creation with the extracted contact fields; confirmation required.
- `send_email` — prepares an email draft; confirmation required.
- `show_location_on_map` — opens a named place, business or address in a map handler.
- `open_wifi_settings` — opens Android Wi-Fi settings.
- `create_calendar_event` — prepares a calendar event from an ISO local date/time; confirmation required.
- `open_app` — opens a launchable app by human-readable launcher label or package name.
- `open_url` — opens an absolute URL.
- `dial_number` — opens the dialer with the number filled in; confirmation required.

## Demo app

The `app` module provides a Jetpack Compose test UI. It can import a `.litertlm` model through the Android document picker, copy it into app-private storage, request the camera permission used for flashlight actions, run an on-device command, and retain the planned action while asking for confirmation when necessary.

Recommended model for the current milestone:

- Repository: `litert-community/functiongemma-270m-ft-mobile-actions`
- File: `mobile_actions_q8_ekv1024.litertlm`
- Runtime: CPU
- Sampling: topK 64, topP 0.95, temperature 0.0

The model file is intentionally not committed to this repository.

## Reproducible AI Edge toolchain

The build is pinned to the same Android toolchain family currently used by Google AI Edge Gallery for LiteRT-LM integration:

- Android Gradle Plugin `8.13.0`
- Kotlin `2.2.0`
- LiteRT-LM Android `0.11.0`
- Gradle `9.2.1` in CI

Pinning LiteRT-LM is intentional. Using `latest.release` can resolve a newer AAR compiled with a newer Kotlin metadata version and break otherwise unchanged Android builds.

## Safety boundary

Actions are classified as `SAFE`, `SENSITIVE`, or `DESTRUCTIVE`. Sensitive and destructive actions require explicit confirmation before execution. Planning and execution are separate, so confirmation does not require a second LLM inference and the action shown to the user is the exact action that will later execute.

## Next milestones

1. Add alarms, timers, sharing, media controls and read-only device-state tools.
2. Add structured parameter metadata to the dynamic ToolRegistry.
3. Add AccessibilityService and semantic UI-tree inspection.
4. Add multi-step Skills such as YouTube search and WhatsApp message preparation.
5. Add model download, integrity verification and device compatibility checks.
6. Add unit/instrumentation tests for tool routing, policy decisions and action execution boundaries.
