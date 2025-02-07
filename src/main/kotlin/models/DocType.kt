package io.github.klahap.fraplin.models

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import io.github.klahap.fraplin.util.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject


data class DocType(
    val module: String?,
    val docTypeName: String,
    val docTypeType: Type,
    val fields: List<DocField>,
) {
    enum class Type(val creatable: Boolean) {
        NORMAL(creatable = true),
        SINGLE(creatable = false),
        CHILD(creatable = true),
    }


    val prettyModule = module?.toSnakeCase()
    val prettyName = docTypeName.toCamelCase(capitalized = true)
    fun getPackageName(context: CodeGenContext) = listOfNotNull(
        context.packageName, "doctype", prettyModule
    ).joinToString(separator = ".")

    fun getRelativePath() = listOfNotNull(
        "doctype", prettyModule, "$prettyName.kt"
    ).joinToString(separator = "/")

    fun getClassName(context: CodeGenContext) = ClassName(packageName = getPackageName(context), prettyName)
    fun getLinkClassName(context: CodeGenContext) = ClassName(packageName = getPackageName(context), prettyName, "Link")
    fun getLinkJsonTypeClassName(context: CodeGenContext) =
        ClassName(packageName = getPackageName(context), prettyName, "Link", "JsonType")

    fun getLinkSerializerClassName(context: CodeGenContext, nullable: Boolean) =
        ClassName(
            packageName = getPackageName(context),
            prettyName,
            "Link",
            if (nullable) "NullableSerializer" else "Serializer",
        )

    context(FileSpec.Builder)
    fun addDummyCode(context: CodeGenContext) {
        clazz(prettyName) {
            addAnnotation(context.annotationFrappeDocType) {
                addMember("docTypeName = %S", docTypeName)
            }
            addDocTypeLinkClass(
                docType = ClassName("", prettyName),
                context = context,
            )
            addSuperinterface(context.getFrappeDocType(docTypeType))
        }
    }

    fun getCode(context: CodeGenContext) = fileBuilder(
        packageName = getPackageName(context),
        fileName = prettyName,
    ) {
        dataClass(name = prettyName) {
            addAnnotation(context.annotationSerializable)
            addAnnotation(context.annotationFrappeDocType) {
                addMember("docTypeName = %S", docTypeName)
            }
            addSuperinterface(context.getFrappeDocType(docTypeType))
            primaryConstructor {
                fields.forEach { field ->
                    addParameter(field.toClassVariableSpec(
                        parent = this@DocType,
                        context = context,
                    ) {})
                    addProperty(
                        field.toClassVariablePropertySpec(
                            parent = this@DocType,
                            context = context,
                        )
                    )
                }
            }
            addFunction("toLink") {
                returns(ClassName("", "Link"))
                addStatement("return Link(name)")
            }
            addDocTypeLinkClass(
                docType = ClassName("", prettyName),
                context = context,
            )
            clazz(name = "Builder") {
                val builderDataName = "_builderData".makeDifferent(fields.map { it.prettyFieldName })
                addSuperinterface(
                    context.docTypeBuilder.parameterizedBy(
                        ClassName("", prettyName),
                        ClassName("", "Builder"),
                    )
                )
                addProperty(
                    name = builderDataName,
                    type = ClassName("kotlin.collections", "MutableMap").parameterizedBy(
                        String::class.asTypeName(),
                        JsonElement::class.asTypeName(),
                    )
                ) {
                    addModifiers(KModifier.PRIVATE)
                    initializer("mutableMapOf()")
                }
                addFunction("get") {
                    addModifiers(KModifier.OVERRIDE, KModifier.OPERATOR)
                    addParameter("field", String::class.asTypeName())
                    returns(JsonElement::class.asTypeName().copy(nullable = true))
                    addStatement("return %L[field]", builderDataName)
                }
                addFunction("set") {
                    addModifiers(KModifier.OVERRIDE, KModifier.OPERATOR)
                    addParameter("field", String::class.asTypeName())
                    addParameter("value", JsonElement::class.asTypeName())
                    addStatement("%L[field] = value", builderDataName)
                }
                addFunction("remove") {
                    addModifiers(KModifier.OVERRIDE)
                    addParameter("field", String::class.asTypeName())
                    returns(JsonElement::class.asTypeName().copy(nullable = true))
                    addStatement("return %L.remove(field)", builderDataName)
                }
                addFunction("clear") {
                    addModifiers(KModifier.OVERRIDE)
                    addStatement("%L.clear()", builderDataName)
                }
                addFunction("build") {
                    addModifiers(KModifier.OVERRIDE)
                    returns(JsonObject::class.asTypeName())
                    addStatement("return JsonObject(%L)", builderDataName)
                }

                fields.forEach { field ->
                    if (field is DocField.Table) {
                        val child = context.docTypes[field.option]!!.getClassName(context)
                        val builder = context.getFrappeDocTableBuilder(child)
                        addFunction(field.prettyFieldName) {
                            addParameter(
                                "block",
                                LambdaTypeName.get(receiver = builder, returnType = Unit::class.asTypeName())
                            )
                            addStatement(
                                "%L[%S] = %T().apply(block).build()",
                                builderDataName,
                                field.fieldName,
                                builder
                            )
                        }
                    }
                    val baseType = field.getBaseTypeName(
                        parent = this@DocType,
                        context = context,
                    )
                    addProperty(
                        name = field.prettyFieldName,
                        type = baseType.copy(nullable = true)
                    ) {
                        mutable(true)
                        delegate(
                            "%T<%T, Builder>(%S, %L)",
                            context.docFieldBuilderDelegation,
                            baseType,
                            field.fieldName,
                            field.toJsonElementTypeInitString(context, mutable = false)
                        )
                    }
                }
            }

            fields.filterIsInstance<DocField.Select>().forEach { field ->
                addType(field.getEnumSpec(context))
            }
        }
    }

    context(FileSpec.Builder)
    fun addHelperCode(context: CodeGenContext) {

        val className = getClassName(context)
        val linkClassName = getLinkClassName(context)
        val builder = className.nestedClass("Builder")
        val tableBuilder = context.getFrappeDocTableBuilder(getClassName(context))
        val reqFields = fields.filter { it.required }

        fun String.toUniqueName() = makeDifferent(blackList = reqFields.map { it.prettyFieldName })
        val builderName = "builder".toUniqueName()
        val blockName = "block".toUniqueName()
        val dataName = "data".toUniqueName()
        val nameName = "name".toUniqueName()

        fun FunSpec.Builder.addReqParams() {
            reqFields.forEach { field ->
                addParameter(
                    field.toClassConstructorVariableSpec(
                        parent = this@DocType,
                        context = context,
                    ) {})
            }
        }

        fun FunSpec.Builder.initBuilderWithMandatory() {
            addStatement("val %L = %T()", builderName, builder)
            reqFields.forEach { field ->
                when (field) {
                    is DocField.Table ->
                        addStatement("%L.%L(%L)", builderName, field.prettyFieldName, field.prettyFieldName)

                    else -> addStatement("%L.%L = %L", builderName, field.prettyFieldName, field.prettyFieldName)
                }
            }
            addStatement("%L.apply(%L)", builderName, blockName)
        }

        addFunction("load$prettyName") {
            receiver(context.frappeSiteService)
            addModifiers(KModifier.SUSPEND)
            returns(className)
            if (docTypeType != Type.SINGLE) {
                addParameter(nameName, String::class.asTypeName())
                addStatement("return this.load(docType=%T::class, name=%L)", className, nameName)
            } else
                addStatement("return this.load(docType=%T::class)", className)
        }
        if (docTypeType != Type.SINGLE)
            addFunction("load${prettyName}OrNull") {
                receiver(context.frappeSiteService)
                addModifiers(KModifier.SUSPEND)
                returns(className.copy(nullable = true))
                addParameter(nameName, String::class.asTypeName())
                addStatement("return this.loadOrNull(docType=%T::class, name=%L)", className, nameName)
            }
        if (docTypeType != Type.SINGLE)
            addFunction("exists$prettyName") {
                receiver(context.frappeSiteService)
                addModifiers(KModifier.SUSPEND)
                returns(Boolean::class)
                addParameter(nameName, String::class.asTypeName())
                addStatement("return this.exists(docType=%T::class, name=%L)", className, nameName)

            }
        if (docTypeType != Type.SINGLE)
            addFunction("delete${prettyName}") {
                receiver(context.frappeSiteService)
                addModifiers(KModifier.SUSPEND)
                addParameter(nameName, String::class.asTypeName())
                addStatement("return this.delete(docType=%T::class, name=%L)", className, nameName)
            }
        addFunction("update$prettyName") {
            receiver(context.frappeSiteService)
            addModifiers(KModifier.SUSPEND)
            returns(className)
            if (docTypeType != Type.SINGLE)
                addParameter(nameName, String::class.asTypeName())
            else
                addStatement("val %L = %S", nameName, className)
            addParameter(blockName, LambdaTypeName.get(receiver = builder, returnType = Unit::class.asTypeName()))
            addStatement(
                "return this.update(docType=%T::class, name=%L, data=%T().apply(%L).build())",
                className,
                nameName,
                builder,
                blockName,
            )
        }
        if (docTypeType != Type.SINGLE)
            addFunction("update") {
                receiver(context.frappeSiteService)
                addModifiers(KModifier.SUSPEND)
                returns(className)
                addParameter("link", linkClassName)
                addParameter(blockName, LambdaTypeName.get(receiver = builder, returnType = Unit::class.asTypeName()))
                addStatement(
                    "return this.update(docType=link.docType, name=link.value, data=%T().apply(%L).build())",
                    builder,
                    blockName,
                )
            }
        if (docTypeType != Type.SINGLE)
            addFunction("loadAll${prettyName}") {
                receiver(context.frappeSiteService)
                addModifiers(KModifier.SUSPEND)
                returns(Flow::class.asClassName().parameterizedBy(className))
                addParameter(
                    blockName,
                    LambdaTypeName.get(
                        receiver = context.getFrappeRequestOptions(className),
                        returnType = Unit::class.asTypeName(),
                    ),
                ) {
                    defaultValue("{}")
                }
                addStatement(
                    "return this.loadAll(docType=%T::class, block=%L)",
                    className,
                    blockName,
                )
            }
        if (docTypeType != Type.SINGLE)
            addFunction("loadAllNamesOf${prettyName}") {
                receiver(context.frappeSiteService)
                addModifiers(KModifier.SUSPEND)
                returns(Flow::class.asClassName().parameterizedBy(linkClassName))
                addParameter(
                    blockName,
                    LambdaTypeName.get(
                        receiver = context.getFrappeRequestOptions(className),
                        returnType = Unit::class.asTypeName(),
                    ),
                ) {
                    defaultValue("{}")
                }
                addStatement(
                    "return this.loadAllNames(docType=%T::class, block=%L)\n.map { %T(it) }",
                    className,
                    blockName,
                    linkClassName,
                )
                addImport("kotlinx.coroutines.flow", "map")
            }

        if (docTypeType.creatable)
            addFunction("create$prettyName") {
                receiver(context.frappeSiteService)
                addModifiers(KModifier.SUSPEND)
                returns(className)
                addReqParams()
                addParameter(blockName, LambdaTypeName.get(receiver = builder, returnType = Unit::class.asTypeName()))
                initBuilderWithMandatory()
                addStatement("return this.create(docType=%T::class, data=%L.build())", className, builderName)
            }
        if (docTypeType.creatable) {
            fun FunSpec.Builder.addCreateFunCommon() {
                receiver(context.frappeSiteService)
                addModifiers(KModifier.SUSPEND)
                returns(className)
                addParameter(buildParameter(name = nameName, type = String::class.asTypeName(), block = {}))
                addReqParams()
                addParameter(
                    name = blockName,
                    type = LambdaTypeName.get(receiver = builder, returnType = Unit::class.asTypeName()),
                ) {
                    defaultValue("{}")
                }
                initBuilderWithMandatory()
                addStatement("val %L = %L.build()", dataName, builderName)
            }
            addFunction("loadOrCreate$prettyName") {
                addCreateFunCommon()
                addCode {
                    beginControlFlow("return try")
                    addStatement("this.load(docType=%T::class, name=%L)", className, nameName)
                    nextControlFlow("catch (e: %T)", context.notFoundException)
                    addStatement("this.create(docType=%T::class, data=%L)", className, dataName)
                    endControlFlow()
                }
            }
            addFunction("createOrLoad$prettyName") {
                addCreateFunCommon()
                addCode {
                    beginControlFlow("return try")
                    addStatement("this.create(docType=%T::class, data=%L)", className, dataName)
                    nextControlFlow("catch (e: %T)", context.conflictException)
                    addStatement("this.load(docType=%T::class, name=%L)", className, nameName)
                    endControlFlow()
                }
            }
            addFunction("updateOrCreate$prettyName") {
                addCreateFunCommon()
                addCode {
                    beginControlFlow("return try")
                    addStatement("this.update(docType=%T::class, name=%L, data=%L)", className, nameName, dataName)
                    nextControlFlow("catch (e: %T)", context.notFoundException)
                    addStatement("this.create(docType=%T::class, data=%L)", className, dataName)
                    endControlFlow()
                }
            }
            addFunction("createOrUpdate$prettyName") {
                addCreateFunCommon()
                addCode {
                    beginControlFlow("return try")
                    addStatement("this.create(docType=%T::class, data=%L)", className, dataName)
                    nextControlFlow("catch (e: %T)", context.conflictException)
                    addStatement("this.update(docType=%T::class, name=%L, data=%L)", className, nameName, dataName)
                    endControlFlow()
                }
            }
        }
        if (docTypeType == Type.CHILD)
            addFunction("add") {
                addAnnotation(JvmName::class.asClassName()) {
                    addMember("%S", "add$prettyName")
                }
                receiver(tableBuilder)
                returns(tableBuilder)
                addReqParams()
                addParameter(blockName, LambdaTypeName.get(receiver = builder, returnType = Unit::class.asTypeName()))
                initBuilderWithMandatory()
                addStatement("this.addJsonValue(%L.build())", builderName)
                addStatement("return this")
            }
    }
}
