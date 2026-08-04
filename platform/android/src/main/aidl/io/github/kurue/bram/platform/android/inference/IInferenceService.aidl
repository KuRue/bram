package io.github.kurue.bram.platform.android.inference;

import io.github.kurue.bram.platform.android.inference.IInferenceCallback;

interface IInferenceService {
    String probe();
    void generate(String requestId, String requestJson, IInferenceCallback callback);
    void cancel(String requestId);
}
