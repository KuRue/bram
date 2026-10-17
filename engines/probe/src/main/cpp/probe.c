// The probe's whole payload: one JNI entry point bound to a class in Bram's own test code, so a
// successful call proves the host process loaded and linked this library from the pack's
// directory. 0x6272616d is "bram".
#include <jni.h>

JNIEXPORT jint JNICALL
Java_io_github_kurue_bram_app_test_EnginePackProbe_probe(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return 0x6272616d;
}
