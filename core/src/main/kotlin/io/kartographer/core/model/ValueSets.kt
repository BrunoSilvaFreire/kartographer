package io.kartographer.core.model

import io.kartographer.core.Provider
import io.kartographer.core.TaskBoundProvider
import java.security.MessageDigest
import kotlin.reflect.full.memberProperties
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.reflect.jvm.isAccessible
import kotlinx.serialization.json.*

abstract class InputSet

class NoInputs : InputSet()

abstract class OutputSet {
    private val declared = linkedMapOf<String, Output<*>>()
    internal var invocation: TaskInvocationRef? = null

    protected inline fun <reified T> output(name: String, secret: Boolean = false): Output<T> =
        declareOutput(name, secret, typeOf<T>())

    @PublishedApi
    internal fun <T> declareOutput(name: String, secret: Boolean, type: KType): Output<T> {
        require(name !in declared) { "Output '$name' is already declared." }
        return Output<T>(name, secret, type).also { declared[name] = it }
    }

    internal fun bind(ref: TaskInvocationRef): OutputSet {
        invocation = ref
        declared.values.forEach { it.bind(ref) }
        return this
    }

    internal fun values(): Map<String, Output<*>> = declared.toMap()

    /** The names this set declares — for a task that inspects what a dependency reports. */
    val outputNames: Set<String> get() = declared.keys.toSet()

    internal suspend fun rendered(maskSecrets: Boolean = true): Map<String, String> = buildMap {
        for ((name, output) in declared) {
            if (!output.isPresent()) continue
            put(name, if (maskSecrets && output.secret) SECRET_MASK else output.getValue().toString())
        }
    }

    internal suspend fun producedValues(): Map<String, Any?> = buildMap {
        for ((name, output) in declared) {
            if (output.isPresent()) put(name, output.getValue())
        }
    }

    internal fun schema(): Map<String, OutputSchema> = declared.mapValues { (_, output) ->
        OutputSchema(output.type.toString(), output.secret)
    }

    internal fun restore(values: Map<String, StoredOutputValue>) {
        values.forEach { (name, stored) -> declared[name]?.setStored(stored) }
    }
}

class NoOutputs : OutputSet()

data class OutputSchema(val type: String, val secret: Boolean)

class Output<T> internal constructor(
    val name: String,
    override val secret: Boolean,
    internal val type: KType
) : TaskBoundProvider<T> {
    private var present = false
    private var value: T? = null
    private var invocationRef: TaskInvocationRef? = null

    fun set(value: T) {
        check(invocationRef == null) { "Cannot set bound output '$name'." }
        require(value != null || type.isMarkedNullable) { "Output '$name' does not accept null." }
        val outputClass = type.classifier as? kotlin.reflect.KClass<*>
        require(value == null || outputClass == null || outputClass.isInstance(value)) {
            "Output '$name' expected $type but received ${value!!::class.qualifiedName}."
        }
        this.value = value
        present = true
    }

    internal fun bind(ref: TaskInvocationRef) {
        invocationRef = ref
    }

    internal fun isPresent(): Boolean = present

    internal fun stored(includeSecret: Boolean = false): StoredOutputValue? =
        if (!present || (secret && !includeSecret)) null else StoredOutputValue(
        (value as Any?)?.javaClass?.name ?: "null",
        value?.toString() ?: ""
    )

    @Suppress("UNCHECKED_CAST")
    internal fun setStored(stored: StoredOutputValue) {
        check(invocationRef == null) { "Cannot restore bound output '$name'." }
        val restored = when (stored.type) {
            "java.lang.Boolean" -> stored.value.toBoolean()
            "java.lang.Byte" -> stored.value.toByte()
            "java.lang.Short" -> stored.value.toShort()
            "java.lang.Integer" -> stored.value.toInt()
            "java.lang.Long" -> stored.value.toLong()
            "java.lang.Float" -> stored.value.toFloat()
            "java.lang.Double" -> stored.value.toDouble()
            "null" -> null
            else -> stored.value
        } as T
        set(restored)
    }

    internal suspend fun getValue(): T {
        if (present) return value as T
        val ref = invocationRef
            ?: throw IllegalStateException("Output '$name' has not been produced.")
        val produced = providerResolutionOrNull()?.producedOutputs
        if (produced?.get(ref.id)?.containsKey(name) != true) {
            throw IllegalStateException("Output '$name' from '${ref.displayName}' has not been produced.")
        }
        return produced.getValue(ref.id)[name] as T
    }

    override suspend fun get(): T = getValue()

    override val description: String
        get() = invocationRef?.let { "<output ${it.displayName}#$name>" } ?: "<output $name>"

    override val producingTask: Task<*, *>?
        get() = invocationRef?.task

    override val producingInvocation: TaskInvocationRef?
        get() = invocationRef
}

data class StoredOutputValue(val type: String, val value: String)

data class TaskInvocationRef(
    val id: String,
    val displayName: String,
    val task: Task<*, *>,
    val configured: Boolean
)

object InputSetCodec {
    fun hash(inputSet: InputSet): String {
        val bytes = canonical(inputSet).toByteArray()
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    fun canonical(inputSet: InputSet): String = canonicalObject(inputSet)

    fun fields(inputSet: InputSet): Map<String, Any?> = properties(inputSet)

    fun applyCanonical(inputSet: InputSet, canonical: String) {
        val root = Json.parseToJsonElement(canonical) as? JsonObject
            ?: throw IllegalArgumentException("InputSet JSON must be an object.")
        populate(inputSet, root)
    }

    private fun populate(target: Any, values: JsonObject) {
        val properties = target::class.memberProperties.associateBy { it.name }
        values.forEach { (name, element) ->
            val property = properties[name]
                ?: throw IllegalArgumentException("Unknown InputSet field '$name'.")
            property.isAccessible = true
            val mutable = property as? KMutableProperty1<Any, Any?>
            if (mutable != null) {
                mutable.setter.call(target, decode(element, property.returnType))
            } else {
                val nested = property.getter.call(target)
                if (nested is InputSet && element is JsonObject) populate(nested, element)
                else throw IllegalArgumentException("InputSet field '$name' is not mutable.")
            }
        }
    }

    private fun decode(element: JsonElement, type: KType): Any? {
        if (element is JsonNull) return null
        val classifier = type.classifier as? kotlin.reflect.KClass<*>
        return when (classifier) {
            String::class -> element.jsonPrimitive.content
            Char::class -> element.jsonPrimitive.content.single()
            Boolean::class -> element.jsonPrimitive.boolean
            Byte::class -> element.jsonPrimitive.int.toByte()
            Short::class -> element.jsonPrimitive.int.toShort()
            Int::class -> element.jsonPrimitive.int
            Long::class -> element.jsonPrimitive.long
            Float::class -> element.jsonPrimitive.float
            Double::class -> element.jsonPrimitive.double
            List::class, MutableList::class -> element.jsonArray.map { decode(it, type.arguments[0].type!!) }
            Set::class, MutableSet::class -> element.jsonArray.mapTo(linkedSetOf()) { decode(it, type.arguments[0].type!!) }
            Map::class, MutableMap::class -> element.jsonObject.mapValues { (_, value) ->
                decode(value, type.arguments[1].type!!)
            }
            else -> when {
                classifier?.java?.isEnum == true -> classifier.java.enumConstants.first {
                    (it as Enum<*>).name == element.jsonPrimitive.content
                }
                classifier != null && InputSet::class.java.isAssignableFrom(classifier.java) -> {
                    val nested = classifier.java.getDeclaredConstructor().newInstance()
                    populate(nested, element.jsonObject)
                    nested
                }
                else -> throw IllegalArgumentException("Unsupported InputSet type $type.")
            }
        }
    }

    private fun canonicalObject(value: Any): String = properties(value)
        .entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (name, item) ->
            "${quote(name)}:${canonicalValue(item, name)}"
        }

    private fun properties(value: Any): Map<String, Any?> = value::class.memberProperties
        .associate { property ->
            property.isAccessible = true
            property.name to property.getter.call(value)
        }

    private fun canonicalValue(value: Any?, field: String): String = when (value) {
        null -> "null"
        is String, is Char -> quote(value.toString())
        is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> value.toString()
        is Enum<*> -> quote(value.name)
        is InputSet -> canonicalObject(value)
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalValue(it, field) }
        is Set<*> -> value.map { canonicalValue(it, field) }.sorted().joinToString(prefix = "[", postfix = "]")
        is Map<*, *> -> value.entries
            .map { entry ->
                val key = entry.key as? String
                    ?: throw IllegalArgumentException("InputSet field '$field' has a non-string map key.")
                key to entry.value
            }
            .sortedBy { it.first }
            .joinToString(prefix = "{", postfix = "}") { (key, item) ->
                "${quote(key)}:${canonicalValue(item, field)}"
            }
        else -> throw IllegalArgumentException(
            "InputSet field '$field' has unsupported type ${value::class.qualifiedName}."
        )
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}

object OutputSetCodec {
    fun canonical(outputs: OutputSet): String = buildJsonObject {
        outputs.values().forEach { (name, output) ->
            if (output.isPresent()) putJsonObject(name) {
                val stored = output.stored(includeSecret = true)!!
                put("type", stored.type)
                put("value", stored.value)
                put("secret", output.secret)
            }
        }
    }.toString()

    fun restore(outputs: OutputSet, canonical: String): OutputSet {
        if (canonical.isBlank()) return outputs
        val values = Json.parseToJsonElement(canonical).jsonObject.mapValues { (_, element) ->
            val item = element.jsonObject
            StoredOutputValue(item.getValue("type").jsonPrimitive.content, item.getValue("value").jsonPrimitive.content)
        }
        outputs.restore(values)
        return outputs
    }
}

internal data class ConfiguredDependency<I : InputSet, O : OutputSet>(
    val task: Task<I, O>,
    val ref: TaskInvocationRef,
    val configure: (I) -> Unit,
    val outputs: O,
    val forceCompletion: Boolean = false
) {
    @Suppress("UNCHECKED_CAST")
    fun configureAny(inputSet: InputSet) = configure(inputSet as I)
}
