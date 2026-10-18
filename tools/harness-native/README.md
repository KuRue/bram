# Native recipe replay check

`recipe_fixture.py` writes a deterministic 930 KiB GGUF with procedural weights. It always prefers
the byte token `A`. It exercises loading, complete token replay, split prompt/decode threads, and
tuned pool attachment. Its output and timings are not model-quality or performance evidence.

The generator requires NumPy and `gguf-py` from Bram's pinned llama.cpp source checkout, which a
native build places beneath `runtime/llamacpp/.cxx/Release/.../_deps/llama_cpp-src/gguf-py`.

```bash
python3 tools/harness-native/recipe_fixture.py \
  --gguf-python /path/to/pinned/llama.cpp/gguf-py \
  --output /tmp/recipe-fixture.gguf

BRAM_EMULATOR_ABI=true ./gradlew -Pbram.applicationIdSuffix=.nativecheck \
  :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell run-as io.github.kurue.bram.app.nativecheck mkdir -p files
adb shell -T run-as io.github.kurue.bram.app.nativecheck tee files/recipe-fixture.gguf \
  < /tmp/recipe-fixture.gguf > /dev/null
adb shell am instrument -w \
  -e class io.github.kurue.bram.app.test.RecipeNativeReplayOnDeviceTest \
  -e recipeFixture /data/user/0/io.github.kurue.bram.app.nativecheck/files/recipe-fixture.gguf \
  io.github.kurue.bram.app.nativecheck.test/io.github.kurue.bram.app.test.BramTestRunner
```

Use a fresh application suffix for this run; it gives the check its own package and storage. The
test is skipped without `recipeFixture`, so the normal cold-AVD suite stays model-free. A successful
opt-in run logs `BramRecipeNativeTest` with full agreement, prompt=2/decode=1 and a custom pool.
The ordinary replay must still report one thread count and no tuned pool. After the run, unload
and remove the test-only installations you created, then install ordinary APKs for the full suite.
