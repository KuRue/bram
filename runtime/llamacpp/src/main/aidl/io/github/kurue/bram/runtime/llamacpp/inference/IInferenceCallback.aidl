package io.github.kurue.bram.runtime.llamacpp.inference;

oneway interface IInferenceCallback {
    void onEvent(String requestId, String eventJson);
}
