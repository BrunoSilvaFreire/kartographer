package io.kartographer.core.plugins

import io.kartographer.core.model.Task
import io.kartographer.core.Property
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

object TaskSerializer {

    fun serializeProperties(task: Task): Map<String, String> {
        val properties = mutableMapOf<String, String>()
        @Suppress("UNCHECKED_CAST")
        val taskClass = task::class as kotlin.reflect.KClass<Any>

        for (prop in taskClass.memberProperties) {
            val name = prop.name
            if (isBaseTaskProperty(name)) continue

            try {
                prop.isAccessible = true
                val value = prop.get(task) ?: continue
                
                val actualValue = if (value is Property<*>) {
                    if (value.isPresent()) value.get() else null
                } else {
                    value
                } ?: continue

                when (actualValue) {
                    is String -> properties[name] = actualValue
                    is Number -> properties[name] = actualValue.toString()
                    is Boolean -> properties[name] = actualValue.toString()
                    is Collection<*> -> {
                        properties[name] = actualValue.filterNotNull().joinToString(",")
                    }
                    else -> properties[name] = actualValue.toString()
                }
            } catch (e: Exception) {
                // Ignore properties that cannot be read
            }
        }
        return properties
    }

    fun deserializeProperties(task: Task, properties: Map<String, String>) {
        @Suppress("UNCHECKED_CAST")
        val taskClass = task::class as kotlin.reflect.KClass<Any>

        for (prop in taskClass.memberProperties) {
            val name = prop.name
            if (isBaseTaskProperty(name)) continue

            val stringValue = properties[name] ?: continue

            try {
                prop.isAccessible = true
                
                val currentValue = prop.get(task)
                if (currentValue is Property<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val mutableProp = currentValue as Property<Any>
                    mutableProp.set(stringValue)
                    continue
                }

                if (currentValue is MutableCollection<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val mutableCollection = currentValue as MutableCollection<Any>
                    mutableCollection.clear()
                    if (stringValue.isNotEmpty()) {
                        mutableCollection.addAll(stringValue.split(","))
                    }
                    continue
                }

                if (prop is KMutableProperty<*>) {
                    val returnType = prop.returnType.classifier
                    val convertedValue: Any? = when (returnType) {
                        String::class -> stringValue
                        Int::class -> stringValue.toIntOrNull()
                        Boolean::class -> stringValue.toBoolean()
                        Double::class -> stringValue.toDoubleOrNull()
                        Long::class -> stringValue.toLongOrNull()
                        MutableList::class, List::class -> {
                            if (stringValue.isEmpty()) mutableListOf<String>()
                            else stringValue.split(",").toMutableList()
                        }
                        else -> stringValue
                    }
                    if (convertedValue != null) {
                        prop.setter.call(task, convertedValue)
                    }
                }
            } catch (e: Exception) {
                // Ignore properties that fail to set
            }
        }
    }

    private fun isBaseTaskProperty(name: String): Boolean {
        return name in setOf(
            "id", "name", "dependencyObjects", "dependencyNames", 
            "inputs", "outputs", "topologyRequirements", 
            "registeredProperties", "declaredOutputs", "upToDateChecks",
            "customFilesToTrack", "customPropertiesToTrack"
        )
    }
}
