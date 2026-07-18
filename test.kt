fun main(args: Array<String>) {
    val newArgs = mutableListOf<String>()
    var i = 0
    val knownOptions = setOf("--ui", "-d", "--project-dir", "-p", "--pipeline", "--help", "-h")
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--") && arg !in knownOptions && args.contains("run")) {
            val propName = arg.substring(2)
            newArgs.add("-P")
            if (i + 1 < args.size && !args[i + 1].startsWith("-")) {
                newArgs.add("$propName=${args[++i]}")
            } else {
                newArgs.add("$propName=true")
            }
        } else {
            newArgs.add(arg)
        }
        i++
    }
    println("Old args: ${args.toList()}")
    println("New args: $newArgs")
}
