package io.github.kurue.bram.runtime.llamacpp.inference;

import io.github.kurue.bram.runtime.llamacpp.inference.IInferenceCallback;

interface IInferenceService {
    String probe();
    String devices();
    String referenceDecode(int tokenCount);
    String teacherForced(in int[] forcedTokens);
    String parseReply(String reply);
    String load(String requestJson);
    int countTokens(String requestJson);
    void generate(String requestId, String requestJson, IInferenceCallback callback);
    void cancel(String requestId);
    String unload();
    String state();
}
