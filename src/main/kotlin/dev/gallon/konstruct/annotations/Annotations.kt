package konstruct.annotations

import kotlin.reflect.KClass

/**
 * Defines a custom serializer for a specific field within a class.
 */
@Target()
@Retention(AnnotationRetention.SOURCE)
annotation class FieldSerializer(
    val name: String,
    val serializer: KClass<*>,
)

/**
 * Groups custom field serializers for a specific target class.
 */
@Target()
@Retention(AnnotationRetention.SOURCE)
annotation class CustomFieldSerializer(
    val targetClass: KClass<*>,
    val fieldSerializer: Array<FieldSerializer>,
)

/**
 * Defines a custom serializer for a specific class type.
 */
@Target()
@Retention(AnnotationRetention.SOURCE)
annotation class CustomClassSerializer(
    val targetClass: KClass<*>,
    val serializer: KClass<*>,
)

/**
 * Generates kotlinx.serialization serializers for the specified classes using the surrogate pattern.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateSerializers(
    val classes: Array<KClass<*>>,
    val excludedSerializersFromModule: Array<KClass<*>> = [],
    val customClassSerializers: Array<CustomClassSerializer> = [],
    val customFieldSerializers: Array<CustomFieldSerializer> = [],
)
