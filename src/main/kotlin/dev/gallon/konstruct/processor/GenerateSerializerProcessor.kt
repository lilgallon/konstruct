package dev.gallon.konstruct.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import dev.gallon.konstruct.annotations.GenerateSerializers
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * KSP Processor responsible for generating surrogate classes and serializers
 * based on the @GenerateSerializers annotation.
 *
 * This processor operates in a few main steps:
 * 1. Collects all @GenerateSerializers annotations.
 * 2. For each annotation, it gathers custom class and field level serializers.
 * 3. Generates a surrogate data class for each target class to facilitate mapping.
 * 4. Generates a KSerializer implementation using the surrogate.
 * 5. Generates a SerializersModule containing all the generated serializers.
 */
class GenerateSerializerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    /**
     * Maps a target class qualified name to a custom serializer qualified name.
     * Populated from @GenerateSerializers(customClassSerializers = [...])
     */
    private val classSerializersMap = mutableMapOf<String, String>()

    /**
     * Maps a field (qualifiedClassName.fieldName) to a custom serializer qualified name.
     * Populated from @GenerateSerializers(customFieldSerializers = [...])
     */
    private val fieldSerializersMap = mutableMapOf<String, String>()

    /**
     * Tracks classes for which a surrogate/serializer has already been generated
     * in the current processing round to avoid duplicate file generation.
     */
    private val processedSerializers = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // Find all symbols annotated with @GenerateSerializers
        val symbols = resolver.getSymbolsWithAnnotation(GenerateSerializers::class.qualifiedName!!)

        // Filter symbols that are not yet ready for processing (e.g. references to ungenerated code)
        val unableToProcess = symbols.filter { !it.validate() }.toList()

        processedSerializers.clear()

        symbols
            .filter { it is KSClassDeclaration && it.validate() }
            .map { it as KSClassDeclaration }
            .forEach { declaration ->
                // Process each annotation independently to support multiple configurations
                processAnnotation(declaration, resolver)
            }

        return unableToProcess
    }

    /**
     * Processes a single @GenerateSerializers annotation found on a class.
     * Extracts configuration, populates serializer maps, and triggers code generation.
     */
    private fun processAnnotation(declaration: KSClassDeclaration, resolver: Resolver) {
        val annotation = declaration.annotations
            .firstOrNull { it.shortName.asString() == "GenerateSerializers" } ?: return

        // Clear maps for each annotation to ensure configurations remain independent
        classSerializersMap.clear()
        fieldSerializersMap.clear()

        // 1. Collect custom class level serializers (e.g. UUID -> UUIDSerializer)
        val customClassSerializers = annotation.findArgumentValue("customClassSerializers") as? List<*> ?: emptyList<Any>()

        customClassSerializers.forEach { annotationValue ->
            if (annotationValue is KSAnnotation) {
                val targetClass = annotationValue.findArgumentValue("targetClass") as? KSType
                val serializerClass = annotationValue.findArgumentValue("serializer") as? KSType

                if (targetClass != null && serializerClass != null) {
                    val targetQualifiedName = targetClass.declaration.qualifiedName?.asString()
                    val serializerQualifiedName = serializerClass.declaration.qualifiedName?.asString()
                    if (targetQualifiedName != null && serializerQualifiedName != null) {
                        classSerializersMap[targetQualifiedName] = serializerQualifiedName
                    }
                }
            }
        }

        // 2. Collect custom field level serializers (e.g. Employee.secretCode -> CustomIntSerializer)
        val customFieldSerializers = annotation.findArgumentValue("customFieldSerializers") as? List<*> ?: emptyList<Any>()

        customFieldSerializers.forEach { customFieldSerializerAnnotation ->
            if (customFieldSerializerAnnotation is KSAnnotation) {
                val targetClass = customFieldSerializerAnnotation.findArgumentValue("targetClass") as? KSType
                val className = targetClass?.declaration?.qualifiedName?.asString()
                val fieldSerializer = customFieldSerializerAnnotation.findArgumentValue("fieldSerializer") as? List<*>

                if (className != null && fieldSerializer != null) {
                    fieldSerializer.forEach { fieldSerializerAnnotation ->
                        if (fieldSerializerAnnotation is KSAnnotation) {
                            val fieldName = fieldSerializerAnnotation.findArgumentValue("name") as? String
                            val serializerClass = fieldSerializerAnnotation.findArgumentValue("serializer") as? KSType

                            if (fieldName != null && serializerClass != null) {
                                val serializerQualifiedName = serializerClass.declaration.qualifiedName?.asString()
                                if (serializerQualifiedName != null) {
                                    fieldSerializersMap["$className.$fieldName"] = serializerQualifiedName
                                }
                            }
                        }
                    }
                }
            }
        }

        val generatedSerializers = mutableListOf<Pair<String, String>>()

        // 3. Collect and process classes specified in the 'classes' argument
        val classes = annotation.findArgumentValue("classes") as? List<*> ?: emptyList<Any>()
        val classTypes = classes.filterIsInstance<KSType>()

        classTypes.forEach { type ->
            val className = type.declaration.qualifiedName?.asString() ?: return@forEach
            val targetClass = resolver.getClassDeclarationByName(resolver.getKSNameFromString(className))

            if (targetClass == null) {
                logger.error("Cannot find class declaration for: $className", declaration)
            } else {
                // Avoid redundant generation of serializers for the same class in the same round
                if (processedSerializers.add(className)) {
                    generateSurrogateAndSerializer(targetClass)
                }
                val simpleName = targetClass.simpleName.asString()
                generatedSerializers.add(className to "${simpleName}Serializer")
            }
        }

        // 4. Collect exclusions for the SerializersModule
        val excludedSerializersFromModule = annotation.findArgumentValue("excludedSerializersFromModule") as? List<*> ?: emptyList<Any>()
        val excludedNames = excludedSerializersFromModule
            .filterIsInstance<KSType>()
            .mapNotNull { it.declaration.qualifiedName?.asString() }

        // 5. Generate the SerializersModule for this specific configuration
        if (generatedSerializers.isNotEmpty()) {
            generateSerializersModule(
                declaration,
                generatedSerializers.filterNot { excludedNames.contains(it.first) },
            )
        }
    }

    /**
     * Generates both the surrogate data class and the KSerializer for a given target class.
     * The serializer delegates its implementation to the surrogate's auto-generated serializer,
     * using the 'mapped' extension to convert between types.
     */
    private fun generateSurrogateAndSerializer(targetClass: KSClassDeclaration) {
        val packageName = targetClass.packageName.asString()
        val className = targetClass.simpleName.asString()
        val surrogateName = "${className}Surrogate"
        val serializerName = "${className}Serializer"

        // Only process public properties with getters
        val properties = targetClass.getAllProperties()
            .filter { it.hasPublicGetter() }
            .toList()

        if (properties.isEmpty()) {
            logger.warn("No public properties found for $className, skipping generation.")
            return
        }

        val file = FileSpec.builder(packageName, serializerName)
            .addImport("dev.gallon.konstruct.serialization", "mapped")

        // Build the surrogate data class which mirrors the target class but is @Serializable
        val surrogateClass = TypeSpec.classBuilder(surrogateName)
            .addModifiers(KModifier.DATA)
            .addAnnotation(ClassName("kotlinx.serialization", "Serializable"))
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlinx.serialization", "SerialName"))
                    .addMember("%S", targetClass.qualifiedName!!.asString())
                    .build(),
            )

        val constructorBuilder = FunSpec.constructorBuilder()
        val toSurrogateParams = mutableListOf<String>()
        val fromSurrogateParams = mutableListOf<String>()

        // Track serializers needed for the @UseSerializers file annotation
        val serializersForFile = mutableSetOf<ClassName>()
        // Track serializers used directly with @Serializable(with = ...)
        val serializersUsedOnProperties = mutableSetOf<ClassName>()

        properties.forEach { prop ->
            val propName = prop.simpleName.asString()
            val propType = prop.type.resolve()
            val propTypeName = propType.toTypeName()

            constructorBuilder.addParameter(propName, propTypeName)

            val propertySpecBuilder = PropertySpec.builder(propName, propTypeName)
                .initializer(propName)

            // Resolve serializer for this specific property (checks field overrides first)
            val fieldKey = "${targetClass.qualifiedName!!.asString()}.$propName"
            val serializerForProperty = resolveSerializer(propType, fieldKey)

            // If a custom serializer is required, add the @Serializable(with = ...) annotation
            if (serializerForProperty != null) {
                propertySpecBuilder.addAnnotation(
                    AnnotationSpec.builder(ClassName("kotlinx.serialization", "Serializable"))
                        .addMember("with = %T::class", serializerForProperty)
                        .build(),
                )
                serializersUsedOnProperties.add(serializerForProperty)
            }

            surrogateClass.addProperty(propertySpecBuilder.build())

            // Collect serializers for any type arguments (e.g. List<UUID> needs UUIDSerializer)
            serializersForFile.addAll(collectAllSerializers(propType))

            toSurrogateParams.add("$propName = it.$propName")
            fromSurrogateParams.add("$propName = it.$propName")
        }

        surrogateClass.primaryConstructor(constructorBuilder.build())

        // Add file-level @UseSerializers for any serializers not explicitly applied to properties
        val serializersOnlyForUseSerializers = serializersForFile - serializersUsedOnProperties
        if (serializersOnlyForUseSerializers.isNotEmpty()) {
            val useSerializersBuilder = AnnotationSpec.builder(ClassName("kotlinx.serialization", "UseSerializers"))
                .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
            serializersOnlyForUseSerializers.forEach { useSerializersBuilder.addMember("%T::class", it) }
            file.addAnnotation(useSerializersBuilder.build())
        }

        file.addType(surrogateClass.build())

        // Create the KSerializer object that handles mapping
        val targetClassName = targetClass.toClassName()
        val serializerType = ClassName("kotlinx.serialization", "KSerializer").parameterizedBy(targetClassName)

        val serializerObject = TypeSpec.objectBuilder(serializerName)
            .addSuperinterface(
                serializerType,
                delegate = CodeBlock.builder()
                    .add("%T.serializer().mapped(\n", ClassName(packageName, surrogateName))
                    .indent()
                    .add("convertForEncoding = { it: %T ->\n", targetClassName)
                    .indent()
                    .add("%T(\n", ClassName(packageName, surrogateName))
                    .indent()
                    .add(toSurrogateParams.joinToString(",\n"))
                    .add("\n")
                    .unindent()
                    .add(")\n")
                    .unindent()
                    .add("},\n")
                    .add("convertForDecoding = { it: %T ->\n", ClassName(packageName, surrogateName))
                    .indent()
                    .add("%T(\n", targetClassName)
                    .indent()
                    .add(fromSurrogateParams.joinToString(",\n"))
                    .add("\n")
                    .unindent()
                    .add(")\n")
                    .unindent()
                    .add("}\n")
                    .unindent()
                    .add(")")
                    .build(),
            )

        file.addType(serializerObject.build())

        // Write the generated file
        file.build().writeTo(codeGenerator, Dependencies(true, *(listOfNotNull(targetClass.containingFile).toTypedArray())))
    }

    /**
     * Resolves the appropriate serializer for a given type.
     * 1. Checks for property-specific overrides (if fieldKey is provided).
     * 2. Checks for global class-level overrides.
     * 3. Checks if an auto-generated serializer is expected for non-serializable classes.
     */
    private fun resolveSerializer(type: KSType, fieldKey: String? = null): ClassName? {
        val qualifiedName = type.declaration.qualifiedName?.asString() ?: return null

        // 1. Check property-specific override
        if (fieldKey != null) {
            fieldSerializersMap[fieldKey]?.let { return serializerFullNameToClassName(it) }
        }

        // 2. Check global class-level override
        classSerializersMap[qualifiedName]?.let { return serializerFullNameToClassName(it) }

        // 3. Fallback to auto-generated serializer if the class is not and should be serializable
        val declaration = type.declaration
        if (declaration is KSClassDeclaration && declaration.classKind == ClassKind.CLASS &&
            !qualifiedName.startsWith("kotlin.") && !qualifiedName.startsWith("java.lang.") &&
            !qualifiedName.startsWith("kotlin.collections.") &&
            declaration.annotations.none { it.shortName.asString() == "Serializable" }) {
            return ClassName(declaration.packageName.asString(), "${declaration.simpleName.asString()}Serializer")
        }

        return null
    }

    private fun serializerFullNameToClassName(fullName: String): ClassName {
        val parts = fullName.split(".")
        return ClassName(parts.dropLast(1).joinToString("."), parts.last())
    }

    /**
     * Recursively collects serializers needed for a type and its type arguments.
     * Used to populate @UseSerializers.
     */
    private fun collectAllSerializers(type: KSType): Set<ClassName> {
        val serializers = mutableSetOf<ClassName>()

        // Resolve serializer for the base type itself
        resolveSerializer(type)?.let { serializers.add(it) }

        // Recursively resolve serializers for type arguments (e.g. List<T>)
        type.arguments.forEach { arg -> arg.type?.resolve()?.let { serializers.addAll(collectAllSerializers(it)) } }

        return serializers
    }

    /**
     * Generates a SerializersModule from a configuration class annotated with @GenerateSerializers.
     * The naming of the module and its property is derived from the annotated class name.
     */
    private fun generateSerializersModule(annotatedClass: KSClassDeclaration, serializers: List<Pair<String, String>>) {
        val packageName = "dev.gallon.konstruct.generated"
        val className = annotatedClass.simpleName.asString()
        val moduleName = "${className}Module"
        val propertyName = className.replaceFirstChar { it.lowercase() } + "Module"

        val file = FileSpec.builder(packageName, moduleName)
            .addImport("kotlinx.serialization.modules", "SerializersModule", "contextual")

        val moduleCode = CodeBlock.builder().add("SerializersModule {\n").indent()
        serializers.forEach { (className, serializerName) ->
            // Extract package and simple name to add clean imports
            val classPackage = className.substringBeforeLast(".")
            val classSimpleName = className.substringAfterLast(".")
            file.addImport(classPackage, classSimpleName, serializerName)

            // Add contextual registration to the module
            moduleCode.add("contextual(%T::class, %L)\n", ClassName.bestGuess(className), serializerName)
        }
        moduleCode.unindent().add("}")

        file.addProperty(
            PropertySpec.builder(propertyName, ClassName("kotlinx.serialization.modules", "SerializersModule"))
                .initializer(moduleCode.build())
                .build(),
        )
        file.build().writeTo(codeGenerator, Dependencies(true, *(listOfNotNull(annotatedClass.containingFile).toTypedArray())))
    }

    /**
     * Checks if a property has a public getter (neither private nor protected).
     */
    private fun KSPropertyDeclaration.hasPublicGetter(): Boolean =
        getter == null || getter!!.modifiers.none { it == Modifier.PRIVATE || it == Modifier.PROTECTED }

    /**
     * Converts a KSType to a Poet TypeName, handling built-ins and parameterized types.
     */
    private fun KSType.toTypeName(): TypeName {
        val declaration = this.declaration
        val baseType = when (declaration.qualifiedName?.asString()) {
            "kotlin.String" -> STRING
            "kotlin.Int" -> INT
            "kotlin.Long" -> LONG
            "kotlin.Boolean" -> BOOLEAN
            "kotlin.Double" -> DOUBLE
            "kotlin.Float" -> FLOAT
            else -> when (declaration) {
                is KSClassDeclaration -> declaration.toClassName()
                is KSTypeAlias -> declaration.toClassName()
                else -> ClassName(declaration.packageName.asString(), declaration.simpleName.asString())
            }
        }
        val typeArgs = this.arguments.mapNotNull { it.type?.resolve()?.toTypeName() }
        val finalType = if (typeArgs.isNotEmpty()) baseType.parameterizedBy(typeArgs) else baseType
        return finalType.copy(nullable = this.isMarkedNullable)
    }

    /**
     * Helper extension to find an argument value by name in a KSAnnotation.
     */
    private fun KSAnnotation.findArgumentValue(name: String): Any? =
        arguments.firstOrNull { it.name?.asString() == name }?.value
}

/**
 * Provider class registered in META-INF/services to allow KSP to instantiate the processor.
 */
class GenerateSerializerProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        GenerateSerializerProcessor(environment.codeGenerator, environment.logger)
}
