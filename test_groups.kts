import io.kartographer.tasks.DefaultTask

group("ops") {
    task<DefaultTask>("rebuildBuildings") {
        action { context ->
            context.executeBash {
                command("echo")
                args("'Rebuilding buildings!'")
            }
        }
    }
    
    task<DefaultTask>("cleanStreets") {
        dependsOn("ops/rebuildBuildings")
        action { context ->
            context.executeBash {
                command("echo")
                args("'Cleaning streets!'")
            }
        }
    }
}

task<DefaultTask>("cityMaintenance") {
    dependsOn("ops")
    action { context ->
        context.executeBash {
            command("echo")
            args("'City maintenance complete!'")
        }
    }
}
