package dev.gallon.konstruct.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import dev.gallon.konstruct.annotations.GenerateSerializers
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

class GenerateSerializerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private val classSerializersMap = mutableMapOf<String, String>()
    private val fieldSerializersMap = mutableMapOf<String, String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(GenerateSerializers::class.qualifiedName!!)
        val unableToProcess = symbols.filter { !it.validate() }.toList()

        symbols
            .filter { it is KSClassDeclaration && it.validate() }
            .forEach { processGenerateSerializers(it as KSClassDeclaration, resolver) }

        return unableToProcess
    }

    private fun processGenerateSerializers(
        declaration: KSClassDeclaration,
        resolver: Resolver,
    ) {
        val annotation = declaration.annotations
            .firstOrNull { it.shortName.asString() == "GenerateSerializers" } ?: return

        @Suppress("UNCHECKED_CAST")
        val classes = (
            annotation.arguments
                .first { it.name?.asString() == "classes" }
                .value as? ArrayList<KSType>
            )?.mapNotNull { it.declaration.qualifiedName?.asString() } ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val excludedSerializersFromModule = (
            annotation.arguments
                .firstOrNull { it.name?.asString() == "excludedSerializersFromModule" }
                ?.value as? ArrayList<KSType>
            )?.mapNotNull { it.declaration.qualifiedName?.asString() } ?: emptyList()

        val customClassSerializers = (
            annotation.arguments
                .firstOrNull { it.name?.asString() == "customClassSerializers" }
                ?.value as? ArrayList<*>
            ) ?: emptyList()

        val customFieldSerializers = (
            annotation.arguments
                .firstOrNull { it.name?.asString() == "customFieldSerializers" }
                ?.value as? ArrayList<*>
            ) ?: emptyList()

        classSerializersMap.clear()
        customClassSerializers.forEach { annotationValue ->
            if (annotationValue is KSAnnotation) {
                val targetClass = annotationValue.arguments
                    .firstOrNull { it.name?.asString() == "targetClass" }
                    ?.value as? KSType

                val serializerClass = annotationValue.arguments
                    .firstOrNull { it.name?.asString() == "serializer" }
                    ?.value as? KSType

                if (targetClass != null && serializerClass != null) {
                    val targetQualifiedName = targetClass.declaration.qualifiedName?.asString()
                    val serializerQualifiedName = serializerClass.declaration.qualifiedName?.asString()

                    if (targetQualifiedName != null && serializerQualifiedName != null) {
                        classSerializersMap[targetQualifiedName] = serializerQualifiedName
                    }
                }
            }
        }

        fieldSerializersMap.clear()
        customFieldSerializers.forEach { customFieldSerializerAnnotation ->
            if (customFieldSerializerAnnotation is KSAnnotation) {
                val targetClass = customFieldSerializerAnnotation.arguments
                    .firstOrNull { it.name?.asString() == "targetClass" }
                    ?.value as? KSType

                val className = targetClass?.declaration?.qualifiedName?.asString()

                val fieldSerializer = customFieldSerializerAnnotation.arguments
                    .firstOrNull { it.name?.asString() == "fieldSerializer" }
                    ?.value as? ArrayList<*>

                if (className != null && fieldSerializer != null) {
                    fieldSerializer.forEach { fieldSerializerAnnotation ->
                        if (fieldSerializerAnnotation is KSAnnotation) {
                            val fieldName = fieldSerializerAnnotation.arguments
                                .firstOrNull { it.name?.asString() == "name" }
                                ?.value as? String

                            val serializerClass = fieldSerializerAnnotation.arguments
                                .firstOrNull { it.name?.asString() == "serializer" }
                                ?.value as? KSType

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

        classes.forEach { className ->
            val targetClass = resolver.getClassDeclarationByName(resolver.getKSNameFromString(className))
            if (targetClass == null) {
                logger.error("Cannot find class: $className", declaration)
            } else {
                generateSurrogateAndSerializer(targetClass)
                val simpleName = targetClass.simpleName.asString()
                generatedSerializers.add(className to "${simpleName}Serializer")
            }
        }

        if (generatedSerializers.isNotEmpty()) {
            generateSerializersModule(
                declaration,
                generatedSerializers.filterNot { excludedSerializersFromModule.contains(it.first) },
            )
        }
    }

    private fun generateSurrogateAndSerializer(targetClass: KSClassDeclaration) {
        val packageName = targetClass.packageName.asString()
        val className = targetClass.simpleName.asString()
        val surrogateName = "${className}Surrogate"
        val serializerName = "${className}Serializer"

        val properties = targetClass.getAllProperties()
            .filter { it.hasPublicGetter() }
            .toList()

        if (properties.isEmpty()) {
            logger.warn("No properties found for $className")
            return
        }

        val file = FileSpec.builder(packageName, serializerName)
            .addImport("konstruct.serialization", "mapped")

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
        val serializersForFile = mutableSetOf<ClassName>()
        val serializersUsedOnProperties = mutableSetOf<ClassName>()

        properties.forEach { prop ->
            val propName = prop.simpleName.asString()
            val propType = prop.type.resolve()
            val propTypeName = propType.toTypeName()

            constructorBuilder.addParameter(propName, propTypeName)

            val propertySpecBuilder = PropertySpec.builder(propName, propTypeName)
                .initializer(propName)

            val fieldKey = "${targetClass.qualifiedName!!.asString()}.$propName"
            val customFieldSerializer = fieldSerializersMap[fieldKey]?.let { serializerFullNameToClassName(it) }
            val serializerForProperty = customFieldSerializer ?: getSerializerForProperty(propType)

            if (serializerForProperty != null) {
                propertySpecBuilder.addAnnotation(
                    AnnotationSpec.builder(ClassName("kotlinx.serialization", "Serializable"))
                        .addMember("with = %T::class", serializerForProperty)
                        .build(),
                )
                serializersUsedOnProperties.add(serializerForProperty)
            }

            surrogateClass.addProperty(propertySpecBuilder.build())

            val serializersInType = if (customFieldSerializer != null) setOf(customFieldSerializer) else collectAllSerializers(propType)
            serializersForFile.addAll(serializersInType)

            toSurrogateParams.add("$propName = it.$propName")
            fromSurrogateParams.add("$propName = it.$propName")
        }

        surrogateClass.primaryConstructor(constructorBuilder.build())

        val serializersOnlyForUseSerializers = serializersForFile - serializersUsedOnProperties
        if (serializersOnlyForUseSerializers.isNotEmpty()) {
            val useSerializersBuilder = AnnotationSpec.builder(ClassName("kotlinx.serialization", "UseSerializers"))
                .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
            serializersOnlyForUseSerializers.forEach { useSerializersBuilder.addMember("%T::class", it) }
            file.addAnnotation(useSerializersBuilder.build())
        }

        file.addType(surrogateClass.build())

        val targetClassName = targetClass.toClassName()
        val serializerType = ClassName("kotlinx.serialization", "KSerializer").parameterizedBy(targetClassName)

        val serializerObject = TypeSpec.objectBuilder(serializerName)
            .addSuperinterface(
                serializerType,
                delegate = CodeBlock.builder()
                    .add("%T.serializer().mapped(\n", ClassName(packageName, surrogateName))
                    .indent()
                    .add("convertForEncoding = {\n")
                    .indent()
                    .add("%T(\n", ClassName(packageName, surrogateName))
                    .indent()
                    .add(toSurrogateParams.joinToString(",\n"))
                    .add("\n")
                    .unindent()
                    .add(")\n")
                    .unindent()
                    .add("},\n")
                    .add("convertForDecoding = {\n")
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
        file.build().writeTo(codeGenerator, Dependencies(true, *(listOfNotNull(targetClass.containingFile).toTypedArray())))
    }

    private fun getSerializerForProperty(propType: KSType): ClassName? {
        val qualifiedName = propType.declaration.qualifiedName?.asString() ?: return null
        if (qualifiedName.startsWith("kotlin.collections.")) return null
        if (qualifiedName.startsWith("kotlin.") || qualifiedName.startsWith("java.lang.")) {
            return classSerializersMap[qualifiedName]?.let { serializerFullNameToClassName(it) }
        }
        classSerializersMap[qualifiedName]?.let { return serializerFullNameToClassName(it) }

        val declaration = propType.declaration
        if (declaration is KSClassDeclaration && declaration.classKind == ClassKind.CLASS &&
            declaration.annotations.none { it.shortName.asString() == "Serializable" }) {
            return ClassName(declaration.packageName.asString(), "${declaration.simpleName.asString()}Serializer")
        }
        return null
    }

    private fun serializerFullNameToClassName(fullName: String): ClassName {
        val parts = fullName.split(".")
        return ClassName(parts.dropLast(1).joinToString("."), parts.last())
    }

    private fun collectAllSerializers(type: KSType): Set<ClassName> {
        val serializers = mutableSetOf<ClassName>()
        val qualifiedName = type.declaration.qualifiedName?.asString()
        if (qualifiedName != null && !qualifiedName.startsWith("kotlin.collections.")) {
            getSerializerInfo(type)?.let { serializers.add(it) }
        }
        type.arguments.forEach { arg -> arg.type?.resolve()?.let { serializers.addAll(collectAllSerializers(it)) } }
        return serializers
    }

    private fun getSerializerInfo(type: KSType): ClassName? {
        val qualifiedName = type.declaration.qualifiedName?.asString() ?: return null
        classSerializersMap[qualifiedName]?.let { return serializerFullNameToClassName(it) }

        val declaration = type.declaration
        if (declaration is KSClassDeclaration && declaration.classKind == ClassKind.CLASS &&
            !qualifiedName.startsWith("kotlin.") && !qualifiedName.startsWith("java.lang.") &&
            declaration.annotations.none { it.shortName.asString() == "Serializable" }) {
            return ClassName(declaration.packageName.asString(), "${declaration.simpleName.asString()}Serializer")
        }
        return null
    }

    private fun generateSerializersModule(annotatedClass: KSClassDeclaration, serializers: List<Pair<String, String>>) {
        val packageName = "konstruct.generated"
        val moduleName = "GeneratedSerializersModule"
        val file = FileSpec.builder(packageName, moduleName)
            .addImport("kotlinx.serialization.modules", "SerializersModule", "contextual")

        val moduleCode = CodeBlock.builder().add("SerializersModule {\n").indent()
        serializers.forEach { (className, serializerName) ->
            val classPackage = className.substringBeforeLast(".")
            val classSimpleName = className.substringAfterLast(".")
            file.addImport(classPackage, classSimpleName, serializerName)
            moduleCode.add("contextual(%T::class, %L)\n", ClassName.bestGuess(className), serializerName)
        }
        moduleCode.unindent().add("}")

        file.addProperty(
            PropertySpec.builder("generatedSerializersModule", ClassName("kotlinx.serialization.modules", "SerializersModule"))
                .initializer(moduleCode.build())
                .build(),
        )
        file.build().writeTo(codeGenerator, Dependencies(true, *(listOfNotNull(annotatedClass.containingFile).toTypedArray())))
    }

    private fun KSPropertyDeclaration.hasPublicGetter(): Boolean =
        getter?.modifiers?.none { it == Modifier.PRIVATE || it == Modifier.PROTECTED } ?: true

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
}

class GenerateSerializerProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        GenerateSerializerProcessor(environment.codeGenerator, environment.logger)
}
