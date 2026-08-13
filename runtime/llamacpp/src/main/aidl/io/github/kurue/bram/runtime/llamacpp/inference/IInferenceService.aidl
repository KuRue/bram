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
    String loadEmbedder(String modelPath, int threads);
    float[] embed(String text);
    String unloadEmbedder();
    // New methods are appended: the binder transaction code of every earlier method is part of
    // the wire contract, and inserting in the middle would shift them for older clients.
    String quantize(String requestJson);
}
