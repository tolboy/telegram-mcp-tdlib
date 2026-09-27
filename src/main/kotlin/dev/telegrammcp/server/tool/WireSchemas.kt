package dev.telegrammcp.server.tool

import java.time.Instant
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor

/** Schemas for explicitly selected wire DTOs, not arbitrary runtime values. */
internal object WireSchemas {
    val string = mapOf<String, Any>("type" to "string")
    val integer = mapOf<String, Any>("type" to "integer")
    val boolean = mapOf<String, Any>("type" to "boolean")
    val date = mapOf<String, Any>("type" to listOf("string", "number"))
    val nil = mapOf<String, Any>("type" to "null")
    fun array(items: Map<String, Any>) = mapOf<String, Any>("type" to "array", "items" to items)
    fun union(vararg choices: Map<String, Any>) = mapOf<String, Any>("anyOf" to choices.toList())
    fun obj(vararg fields: Pair<String, Map<String, Any>>) = obj(fields.toMap())
    fun obj(fields: Map<String, Map<String, Any>>, required: List<String> = fields.keys.toList()) = mapOf<String, Any>(
        "type" to "object", "properties" to fields, "required" to required, "additionalProperties" to false,
    )

    fun type(type: KType): Map<String, Any> {
        val klass = type.classifier as? KClass<*> ?: error("Unresolved wire type: $type")
        val schema = when (klass) {
            String::class -> string
            Boolean::class -> boolean
            Int::class, Long::class, Short::class, Byte::class -> integer
            Double::class, Float::class -> mapOf("type" to "number")
            Instant::class -> date
            List::class, Set::class, Collection::class -> array(type(requireNotNull(type.arguments.single().type)))
            else -> when {
                klass.java.isEnum -> string + ("enum" to klass.java.enumConstants.map { (it as Enum<*>).name })
                klass.isData -> {
                    val parameters = requireNotNull(klass.primaryConstructor).parameters
                    obj(parameters.associate { requireNotNull(it.name) to type(it.type) },
                        parameters.filterNot { it.isOptional || it.type.isMarkedNullable }.map { requireNotNull(it.name) })
                }
                else -> error("No wire schema for $type; register an explicit shape")
            }
        }
        return if (type.isMarkedNullable) union(schema, nil) else schema
    }
}
