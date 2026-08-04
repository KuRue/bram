package io.github.kurue.bram.platform.android.inference;

oneway interface IInferenceCallback {
    void onEvent(String requestId, String eventJson);
}
