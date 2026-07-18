import kotlin.script.experimental.annotations.KotlinScript
import kotlin.reflect.full.declaredMemberProperties

val kClass = KotlinScript::class
kClass.declaredMemberProperties.forEach { println(it.name) }
