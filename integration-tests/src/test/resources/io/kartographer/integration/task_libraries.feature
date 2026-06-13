Feature: Task Libraries (Go, Proto, Gradle) Integration
  Verify that specialized tasks from the go, proto, and gradle libraries compile
  their execute arguments correctly and build valid commands.

  Scenario: Execute a GoBuild task and check command generation
    Given a GoBuild task named "my-go-build" with output "bin/app" and mainFile "cmd/main.go"
    When we dry-run the GoBuild task "my-go-build"
    Then the executed command should start with "go build -o bin/app cmd/main.go"

  Scenario: Execute a ProtoCompile task and check command generation
    Given a ProtoCompile task named "my-proto-compile" with protoPath "src/proto", goOut "gen/go", and files "user.proto, auth.proto"
    When we dry-run the ProtoCompile task "my-proto-compile"
    Then the executed command should start with "protoc --proto_path=src/proto --go_out=gen/go"
    And the executed command should end with "user.proto auth.proto"

  Scenario: Execute a GradleTask and check command generation
    Given a GradleTask named "my-gradle-task" with buildFile "build.gradle.kts", projectDir "subproject", using wrapper, and tasks "clean, test"
    When we dry-run the GradleTask "my-gradle-task"
    Then the executed command should start with "./gradlew -b build.gradle.kts -p subproject clean test"
