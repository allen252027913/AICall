# Local AI Call Android Demo

[English](README.md) | [中文](README_zh.md)

An arm64 Android demo for a local, voice-driven “call + screen sharing” experience. It keeps a user-visible foreground call session, runs streaming Chinese ASR and PP-OCRv5 on-device, can show the latest frame in picture-in-picture (PiP), stores successful OCR captures in a private cache feed, and can execute opt-in accessibility commands.

This project is a technical demo, not a Doubao client clone. Android foreground services, an ongoing notification, and a bounded partial WakeLock improve continuity; they do not make the process immortal. Force-stop, system/OEM reclamation, lock-screen behavior, or revoked projection can end the session.

## Implemented features

- WeChat scrolling screenshots: capture the current mini-program/article through the existing projection session, align overlapping frames, and save a Bitmap, private PNG, and gallery copy. See [setup, library research, and limitations](docs/WECHAT_LONG_SCREENSHOT.md).
- Continuous 16 kHz streaming Chinese ASR with sherpa-onnx endpoint detection.
- Immediate acknowledgement for recognized intents, followed by one token-guarded result.
- During TTS, only the exact normalized phrase `先停一下` interrupts playback; other speech is ignored until the turn finishes.
- Explicit MediaProjection authorization and on-demand full-screen frame capture with up to three attempts.
- Local PP-OCRv5 analysis, current frame/text preview, and source package/class inference from accessibility window events.
- CPU OCR preloads on a background thread when the app Activity opens. One process-owned instance is reused across calls, with serialized inference and retryable initialization; model memory remains allocated until process exit. `LocalCallOcr` logs initialization and acquisition times.
- App-private history feed with JPEG + OCR text, newest first, capped at 50 entries.
- Tap history images for full-screen viewing with pinch zoom (1–5×) and pan. Long-press a thumbnail or full-screen image to save the unchanged original to the local “识别记录” album; private records are retained. Android 8–9 requests storage permission only when saving; Android 10+ does not require photo-reading permission.
- Optional PiP: compact text-only mode before the first capture, then a frame-derived portrait/landscape ratio; the PiP Activity closes and hides its cached frame when the call or projection ends.
- Optional acoustic speaker-input mode and generic accessibility gestures/clicks.
- Voice-driven JD demo flow: `我的 → 更多 → 一键领取`, with two explicitly scoped coordinate fallbacks.
- Injectable product-recognition request/response contract. The standalone default is deliberately unavailable and performs no network I/O.

The production manifest declares `INTERNET` for recognition of stitched WeChat screenshots; it does not request `ACCESS_NETWORK_STATE`.

## Build and run

Requirements: JDK 21, Android SDK with compile SDK 36.1, NDK 28.1, CMake 3.22+, and an arm64-v8a device running Android 8.0/API 26 or later.

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The bundled ASR/OCR models and native runtime make the APK relatively large; only `arm64-v8a` is packaged.

On a device:

1. Start the app and grant microphone permission when starting a call. Android 13+ may also request notification permission.
2. Tap **Share screen** and approve the system MediaProjection dialog. A new projection session requires a new system grant.
3. If automation or source-app inference is needed, open Accessibility settings from the call screen and explicitly enable **Local AI Call operations**.
4. Leave the app while the call is active. PiP is entered only when PiP is enabled and screen sharing is active.
5. End the call from the app or the ongoing notification.

Denied microphone/notification grants and a cancelled or revoked screen-share grant are shown on the call screen with a retry or system-settings action. Notification permission affects notification visibility; it is not a keep-alive permission.

## Supported voice commands

| Intent | Examples | Behavior |
|---|---|---|
| Read screen | `帮我看看当前屏幕` / `屏幕上有什么` | Capture one fresh frame, run local OCR, show/speak the result, and cache successful non-empty OCR. |
| Product | `帮我看一下这个商品` | Capture and encode the current frame, then call the injected gateway. The standalone gateway replies `独立版未配置商品识别服务`. |
| Interrupt | `先停一下` | The only phrase that stops current acknowledgement/result TTS. |
| Swipe | `下一页`, `上一页`, `向上滑`, `向下滑`, `向左滑`, `向右滑` | Dispatch a normalized accessibility gesture. |
| List item | `第一个`, `第3个列表项` | Click a 1-based collection/list item when accessible metadata exists. |
| Text control | `点下关注按钮`, `点击更多` | Match accessible text/content description, then click the node or nearest clickable ancestor. |
| JD demo | `去京东打卡` | Launch JD and attempt `我的 → 更多 → 一键领取`. |

Generic accessibility commands never use a coordinate fallback. The JD-only fallbacks are physical pixels `[916,475]` for `更多` and `[500,574]` for `一键领取`; they are device/version-sensitive and must be validated on the target phone.

## Privacy and permissions

- Audio is streamed to the bundled ASR model; this app does not persist recordings.
- Screen pixels are available only after the system MediaProjection grant. Frames are consumed on demand, not continuously OCR-processed.
- Successful non-empty OCR captures are stored under the app-private `cacheDir/local_call_history` as JPEG quality 85 plus a local index. The cache is capped at 50 entries and can be removed by Android or by clearing app storage.
- After long-screenshot stitching succeeds, the stitched image is uploaded to the configured pre-production product analysis endpoint and recognition results are logged under `zunyu`. Microphone audio, transcripts, local OCR text and accessibility node contents are not uploaded by this flow.
- Accessibility is optional and highly privileged. When enabled, it can observe window package/class labels and inspect/click accessible nodes in other apps. Disable it in system settings when it is no longer needed.
- “Include speaker sound” still records through the microphone with echo cancellation/noise suppression disabled. It is not digital playback capture.
- Changing that speaker-input preference while no call is active writes only app preferences; it does not retain a started `VoiceCallService`.

## Known limits

- OCR cannot fully understand photos, video, charts, or complex layouts like a multimodal model.
- Accessibility reports package/class names from window events; it does not expose a guaranteed Android Activity stack, and third-party node quality varies.
- PiP is hidden and given compositor settle time before capture, but Android/OEM composition timing means exclusion from every frame cannot be guaranteed.
- The original product-command gateway remains unavailable. Long screenshots use a separate HTTPS client; no MTop SDK or Alibaba host dependency is included.
- JD resource IDs, page timing, and fixed-pixel fallbacks can change between JD releases or display configurations.
- MediaProjection, foreground-service policy, PiP, audio routing, accessibility, and JD automation require real-device verification across Android/OEM versions.

For architecture, ownership, and the device checklist, see [docs/LOCAL_AI_CALL_TECHNICAL_ANALYSIS.md](docs/LOCAL_AI_CALL_TECHNICAL_ANALYSIS.md).

## Attribution

See [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE). The demo retains attribution for [PPOCRv5-Android](https://github.com/iFleey/PPOCRv5-Android), [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR), [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), [LiteRT](https://ai.google.dev/edge/litert), and [Abseil](https://abseil.io/).
