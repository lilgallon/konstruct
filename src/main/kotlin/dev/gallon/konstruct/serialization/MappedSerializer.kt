package dev.gallon.konstruct.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A serializer that maps between a target type [T] and a surrogate type [S].
 */
class MappedSerializer<T, S>(
    private val surrogateSerializer: KSerializer<S>,
    private val convertForEncoding: (T) -> S,
    private val convertForDecoding: (S) -> T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = surrogateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: T) {
        surrogateSerializer.serialize(encoder, convertForEncoding(value))
    }

    override fun deserialize(decoder: Decoder): T {
        return convertForDecoding(surrogateSerializer.deserialize(decoder))
    }
}

/**
 * Extension function to create a [MappedSerializer] from an existing serializer.
 */
fun <T, S> KSerializer<S>.mapped(
    convertForEncoding: (T) -> S,
    convertForDecoding: (S) -> T,
): KSerializer<T> = MappedSerializer(this, convertForEncoding, convertForDecoding)
