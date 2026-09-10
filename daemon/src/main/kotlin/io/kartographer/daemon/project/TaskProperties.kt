package io.kartographer.daemon.project

import io.kartographer.core.Property
import io.kartographer.core.model.Node
import io.kartographer.core.model.Task
import io.kartographer.core.model.InputSetCodec
import io.kartographer.core.scheduler.MaterializedInputs
import java.lang.reflect.ParameterizedType

/**
 * Reflection over a task's `Property` fields.
 *
 * The declaring field is the only place a property's name and value type are recorded —
 * [Property] itself carries the CLI metadata but not the type — so both describing a task for
 * `--task-help` and coercing a `-P` string to the right type have to go through here.
 *
 * This used to live in the CLI; it moved with execution, since the daemon is now what holds
 * the task instances.
 */
object TaskProperties {

    data class Declared(
        val fieldName: String,
        val property: Property<*>,
        val valueType: Class<*>?
    )

    fun declaredOn(task: Task<*, *>): List<Declared> {
        val declared = mutableListOf<Declared>()
        // Walk up the hierarchy: a task type usually declares its properties on a superclass.
        generateSequence(task::class.java as Class<*>) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { Property::class.java.isAssignableFrom(it.type) }
            .forEach { field ->
                field.isAccessible = true
                val property = field.get(task) as? Property<*> ?: return@forEach
                val generic = field.genericType
                val valueType = (generic as? ParameterizedType)
                    ?.actualTypeArguments
                    ?.firstOrNull() as? Class<*>
                declared.add(Declared(field.name, property, valueType))
            }
        return declared
    }

    /** Simple type name for display, matching what `--task-help` printed locally. */
    fun typeNameOf(declared: Declared): String = declared.valueType?.simpleName ?: "Unknown"

    /**
     * Applies raw `-P name=value` strings to the properties of [nodes] for the duration of
     * [block], coercing each to the type its field declares and restoring the previous state
     * afterwards.
     *
     * [materialize] only holds the values while inputs are built, which is enough for a task
     * that reads its properties in `buildInputs`, but not for one that reads `property.get()`
     * inside `execute` — that used to see the un-set value and silently ignore the flag. The
     * restore keeps a value from sticking to the task instance the daemon reuses for the next
     * run.
     *
     * @throws IllegalArgumentException if a name matches no property, so an unknown flag is
     * still a hard error rather than a silently ignored option.
     */
    suspend fun <T> withCliValues(nodes: Collection<Node>, values: Map<String, String>, block: suspend () -> T): T {
        if (values.isEmpty()) return block()

        val tasks = nodes.filterIsInstance<Task<*, *>>()
        val typeByProperty = tasks.flatMap { declaredOn(it) }.associate { it.property to it.valueType }
        val byCliName = tasks
            .flatMap { it.registeredProperties }
            .filter { it.cliName != null }
            .associateBy { it.cliName!! }

        val snapshots = mutableMapOf<Property<*>, Property.Snapshot<*>>()
        try {
            for ((name, raw) in values) {
                val property = byCliName[name] ?: throw IllegalArgumentException("Unknown property --$name")
                snapshots.putIfAbsent(property, property.snapshot())
                @Suppress("UNCHECKED_CAST")
                (property as Property<Any>).set(coerce(raw, typeByProperty[property]))
            }
            return block()
        } finally {
            snapshots.forEach { (property, snapshot) ->
                @Suppress("UNCHECKED_CAST")
                (property as Property<Any>).restore(snapshot as Property.Snapshot<Any>)
            }
        }
    }

    fun validateCliValues(nodes: Collection<Node>, values: Map<String, String>) {
        if (values.isEmpty()) return
        val names = nodes
            .flatMap { it.registeredProperties }
            .mapNotNull { it.cliName }
            .toSet()
        values.keys.firstOrNull { it !in names }?.let { throw IllegalArgumentException("Unknown property --$it") }
    }

    suspend fun materialize(
        task: Task<*, *>,
        context: io.kartographer.core.model.ExecutionContext,
        values: Map<String, String>
    ): MaterializedInputs {
        val declared = declaredOn(task)
        val byCliName = declared.mapNotNull { item ->
            item.property.cliName?.let { it to item }
        }.toMap()
        val snapshots = task.registeredProperties.associateWith { property -> property.snapshot() }
        return try {
            values.forEach { (name, raw) ->
                val item = byCliName[name] ?: return@forEach
                @Suppress("UNCHECKED_CAST")
                (item.property as Property<Any>).set(coerce(raw, item.valueType))
            }
            val inputs = task.buildInputsAny(context)
            val cliFields = values.keys.mapNotNull { cliName ->
                byCliName[cliName]?.fieldName?.let { fieldName -> fieldName to cliName }
            }.toMap()
            MaterializedInputs(inputs, cliFields, InputSetCodec.fields(inputs))
        } finally {
            snapshots.forEach { (property, snapshot) ->
                @Suppress("UNCHECKED_CAST")
                (property as Property<Any>).restore(snapshot as Property.Snapshot<Any>)
            }
        }
    }

    private fun coerce(raw: String, targetClass: Class<*>?): Any = when (targetClass) {
        java.lang.Boolean::class.java, Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType ->
            raw.toBoolean()
        java.lang.Integer::class.java, Int::class.javaObjectType, Int::class.javaPrimitiveType ->
            raw.toInt()
        java.lang.Long::class.java, Long::class.javaObjectType, Long::class.javaPrimitiveType ->
            raw.toLong()
        java.lang.Double::class.java, Double::class.javaObjectType, Double::class.javaPrimitiveType ->
            raw.toDouble()
        else -> raw
    }
}
