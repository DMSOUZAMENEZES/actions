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
   -> Android API / Intent / AccessibilityService
```

LiteRT-LM automatic tool execution is disabled. The language model is responsible only for function selection and argument extraction. Android execution occurs after the runtime has converted the tool call into a typed action and evaluated its risk policy.

## Implemented tools

The runtime supports the MobileActions-270M function set plus runtime extensions:

- `flashlight_on` / `flashlight_off` — controls the torch with explicit Android CAMERA permission handling.
- `create_contact` — opens Android contact creation with extracted contact fields; confirmation required.
- `send_email` — prepares an email draft; confirmation required.
- `show_location_on_map` — opens a named place, business or address in a map handler.
- `open_wifi_settings` — opens Android Wi-Fi settings.
- `create_calendar_event` — prepares a calendar event from an ISO local date/time; confirmation required.
- `open_app` — opens a launchable app by human-readable launcher label or package name.
- `open_url` — opens an absolute URL.
- `dial_number` — opens the dialer with the number filled in; confirmation required.

## Accessibility runtime

The runtime now contains an explicit Android `AccessibilityService` layer for visible-UI automation. It is disabled until the user enables it from Android Accessibility settings.

The service exposes a semantic UI snapshot instead of screenshots. Each visible node receives a path-like ID such as `0.2.1` and includes text, content description, view ID, class, bounds and interaction capabilities.

Low-level tools currently available:

- `read_ui_tree` — reads the active accessibility tree and returns semantic node IDs.
- `click_ui_node` — clicks a semantic node; confirmation required because arbitrary clicks can have external effects.
- `set_ui_text` — enters text in an editable node; confirmation required for the generic primitive.
- `scroll_ui_forward` — scrolls a semantic node.
- `accessibility_back` — performs Android Back.

These generic primitives are intentionally conservative. High-level skills can be safer because their behavior is constrained to a known task.

## First multi-step skill: YouTube search

`youtube_search(query)` is the first constrained skill. It performs:

```text
open YouTube
   -> wait for the YouTube accessibility tree
   -> locate Search semantically
   -> activate Search
   -> locate the editable query field
   -> enter the query
   -> submit through IME or a semantic search control
```

This skill is classified `SAFE` because it is limited to navigation and search and does not publish, send, purchase, delete or otherwise commit external state. The generic click/text tools remain confirmation-gated.

Example goal:

```text
Abra o YouTube e pesquise Google AI Edge
```

FunctionGemma is instructed to prefer `youtube_search` over arbitrary low-level UI operations when the goal matches the constrained skill.

## Demo app

The `app` module provides a Jetpack Compose test UI. It can import a `.litertlm` model through the Android document picker, copy it into app-private storage, request the camera permission used for flashlight actions, open Android Accessibility settings, run an on-device command, and retain the planned action while asking for confirmation when necessary.

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

The accessibility layer does not silently grant itself access. Android requires explicit user enablement of the service. Generic arbitrary UI input is not treated as automatically safe; constrained skills are preferred so permissions and policy can be tied to a specific outcome.

## Next milestones

1. Stabilize CI and add unit/instrumentation coverage for accessibility snapshots and policy boundaries.
2. Add a constrained WhatsApp `prepare_message` skill that stops before sending.
3. Add a separate confirmation-gated `send_prepared_message` step.
4. Add alarms, timers, sharing, media controls and read-only device-state tools.
5. Add model download, integrity verification and device compatibility checks.
6. Add a reusable Skill Engine and skill execution trace for multi-step automations.
