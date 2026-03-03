package com.bones.gateway.service;

import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeEmbeddingService {

    private static final int EMBEDDING_DIM = 64;

    public float[] embed(String text) {
        float[] vector = new float[EMBEDDING_DIM];
        if (text == null || text.isBlank()) {
            return vector;
        }
        String normalized = text.toLowerCase(Locale.ROOT).trim();
        String[] tokens = normalized.split("[\\s,，。.;；:：/|\\\\_\\-]+");
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            int bucket = Math.floorMod(token.hashCode(), EMBEDDING_DIM);
            float weight = 1.0f + Math.min(6, token.length()) * 0.1f;
            vector[bucket] += weight;
        }
        normalize(vector);
        return vector;
    }

    public String serialize(float[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.toString();
    }

    public float[] deserialize(String raw) {
        if (raw == null || raw.isBlank()) {
            return new float[0];
        }
        String[] parts = raw.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                vector[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException ignored) {
                vector[i] = 0f;
            }
        }
        return vector;
    }

    public double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0) {
            return 0;
        }
        int size = Math.min(left.length, right.length);
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < size; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm <= 0 || rightNorm <= 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private void normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm <= 0) {
            return;
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
    }
}
