package io.github.yuri_hack.rag_knowledge_qa.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class VectorUtils {

    public static byte[] floatArray2Bytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN); // RediSearch 默认小端
        for (float f : vector) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    public static float[] fromByteArray(byte[] bytes) {
        if (bytes == null || bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("Invalid byte array length for float vector");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}