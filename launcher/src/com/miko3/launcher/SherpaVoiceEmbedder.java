package com.miko3.launcher;

import android.os.Process;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

/**
 * Owner 2026-10-02: VoiceId's embedder, sherpa-onnx's speaker embedding extractor over
 * the bundled 3D-Speaker CAM++ model (assets/voiceid/model.onnx). One ONNX thread, so an
 * embedding takes at most one core from the ears' recogniser. Robot 2026-10-03: the calling
 * thread (VoiceId's own) runs at Process.THREAD_PRIORITY_DEFAULT, not background: at the
 * lowest priority an embedding took 1.8-8 s under Explore's load and came a turn late, and
 * the result is wanted within the same turn. Made and used only on the voice thread.
 */
final class SherpaVoiceEmbedder implements VoiceId.Embedder {
    private final SpeakerEmbeddingExtractor extractor;
    private boolean prioritised;

    SherpaVoiceEmbedder(String modelPath) {
        prioritise();
        extractor = new SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig.builder()
                .setModel(modelPath)
                .setNumThreads(1)
                .setDebug(false)
                .setProvider("cpu")
                .build());
    }

    int dim() {
        return extractor.getDim();
    }

    private void prioritise() {
        if (!prioritised) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            prioritised = true;
        }
    }

    @Override
    public float[] embed(float[] samples, int n) {
        prioritise();
        float[] audio = samples;
        if (n != samples.length) {
            audio = new float[n];
            System.arraycopy(samples, 0, audio, 0, n);
        }
        OnlineStream stream = extractor.createStream();
        try {
            stream.acceptWaveform(audio, VoiceTuning.SAMPLE_RATE);
            stream.inputFinished();
            if (!extractor.isReady(stream)) {
                return null;
            }
            return extractor.compute(stream);
        } finally {
            stream.release();
        }
    }
}
