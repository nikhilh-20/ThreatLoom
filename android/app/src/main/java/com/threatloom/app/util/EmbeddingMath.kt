package com.threatloom.app.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class EmbeddingMath @Inject constructor() {

    fun floatsToBlob(floats: List<Float>): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun blobToFloats(blob: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(blob.size / 4) { buffer.getFloat() }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }

    fun rankBySimilarity(
        queryEmbedding: FloatArray,
        embeddings: List<Pair<Long, ByteArray>>,
        topK: Int = 15
    ): List<Pair<Long, Float>> {
        return embeddings.map { (articleId, blob) ->
            val emb = blobToFloats(blob)
            articleId to cosineSimilarity(queryEmbedding, emb)
        }
            .sortedByDescending { it.second }
            .take(topK)
    }
}
