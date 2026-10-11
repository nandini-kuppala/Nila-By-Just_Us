# Adding Little Signs to Nila

The feature was built standalone on Nila 2.7.1's exact versions (AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, Room 2.6.1, CameraX 1.4.1, MediaPipe 0.10.18, GenieX 0.4.0). No version bump is needed.

## Steps

1. **Copy the modules.** Copy `skin-core`, `skin-data`, `skin-ai` and `skin-ui` into `Nila-By-Just_Us/android/`, then `include()` them in `settings.gradle.kts`.
2. **Add catalog entries.** Add the plugin `kotlin-jvm` and the libraries `android-library`, `androidx-exif`, `guava` and `androidx-work`, as in this repo's `libs.versions.toml`.
3. **Depend on the UI module.** In `app/build.gradle.kts`, add `implementation(project(":skin-ui"))`.
4. **Share the NPU model.** Nila already holds Intern3.5-VL in `NpuVlm` and enforces one model on the NPU through `NpuArbiter`. Give the feature an adapter instead of a second model:

   ```kotlin
   class NilaVision(private val vlm: NpuVlm) : VisionModel {
       override val status = MutableStateFlow<VisionModel.Status>(VisionModel.Status.Installed)
       override suspend fun ensureLoaded() = vlm.start()
       override suspend fun look(frame: Bitmap, systemPrompt: String, userPrompt: String) = vlm.look(frame, systemPrompt, userPrompt)
   }
   // NilaApp.onCreate:
   SkinServices.init(this, vision = NilaVision(sharedNpuVlm))
   ```

   If the safety watch is running, it keeps priority, as Nila's arbiter already decides.
5. **Add one entry point.** Put a "Little Signs" card on Nila's Health screen that shows `SkinApp(onExit = { ... })` full screen. The feature has its own back stack.
6. **Theme.** The feature's theme and Kit are verbatim copies of Nila's, renamed into `com.nila.skin.ui`. Either keep them, or swap their imports to Nila's `com.nila.ui.theme` and `com.nila.ui.components`.
7. **Data.** The feature uses its own `nila_skin.db` and `files/nila-skin/photos`, so Nila's schema and migrations are untouched.
8. **Allergies.** Optional: copy patterns from Food & reactions into Nila's `HealthRecords.allergies` only after the parent confirms. Never do it automatically.

## What does not change in Nila

- No existing screen, schema, model or permission behaviour changes.
- The feature adds only:
  - one camera use, already declared;
  - `READ_MEDIA_IMAGES`, requested only when the parent taps "Scan my gallery";
  - `POST_NOTIFICATIONS`, requested only when the parent taps "Remind me" (reminders are silent and run on WorkManager);
  - one FileProvider for sharing the report PDF.
