Feature: Task Libraries (Go, Proto, Gradle) Integration
  Verify that specialized tasks from the go, proto, and gradle libraries compile
  their execute arguments correctly and build valid commands.

  Scenario: Execute a GoBuild task and check command generation
    Given a GoBuild task named "my-go-build" with output "bin/app" and mainFile "cmd/main.go"
    When we dry-run the GoBuild task "my-go-build"
    Then the executed command should start with "go build -o bin/app cmd/main.go"

  Scenario: Execute a GoBuild task with environment, null output and null mainFile
    Given a GoBuild task named "go-build-env" with environment "GOOS=linux, GOARCH=amd64"
    When we dry-run the GoBuild task "go-build-env"
    Then the executed command should start with "go build ."

  Scenario: Execute a GoTest task and check command generation
    Given a GoTest task named "my-go-test" with packages "pkg/foo, pkg/bar"
    When we dry-run the GoTest task "my-go-test"
    Then the executed command should start with "go test pkg/foo pkg/bar"

  Scenario: Execute a GoMod task and check command generation
    Given a GoMod task named "my-go-mod" with action "tidy"
    When we dry-run the GoMod task "my-go-mod"
    Then the executed command should start with "go mod tidy"

  Scenario: Execute a ProtoCompile task and check command generation
    Given a ProtoCompile task named "my-proto-compile" with protoPath "src/proto", goOut "gen/go", and files "user.proto, auth.proto"
    When we dry-run the ProtoCompile task "my-proto-compile"
    Then the executed command should start with "protoc --proto_path=src/proto --go_out=gen/go"
    And the executed command should end with "user.proto auth.proto"

  Scenario: Execute a ProtoCompile task with all options set
    Given a ProtoCompile task named "proto-all-options" with goGrpcOut "gen/grpc", pythonOut "gen/py", javaOut "gen/java", extraArgs "--experimental_allow_proto3_optional", and files "item.proto"
    When we dry-run the ProtoCompile task "proto-all-options"
    Then the executed command should start with "protoc --go-grpc_out=gen/grpc --python_out=gen/py --java_out=gen/java --experimental_allow_proto3_optional item.proto"

  Scenario: Execute a ProtoCompile task with no proto files
    Given a ProtoCompile task named "proto-no-files" with no proto files
    When we dry-run the ProtoCompile task "proto-no-files" and expect failure
    Then the task execution should fail with IllegalArgumentException

  Scenario: Execute a GradleTask and check command generation
    Given a GradleTask named "my-gradle-task" with buildFile "build.gradle.kts", projectDir "subproject", using wrapper, and tasks "clean, test"
    When we dry-run the GradleTask "my-gradle-task"
    Then the executed command should start with "./gradlew -b build.gradle.kts -p subproject clean test"

  Scenario: Execute a GradleTask without wrapper and with extra arguments
    Given a GradleTask named "gradle-no-wrapper" with extraArgs "--no-daemon", not using wrapper, and tasks "build"
    When we dry-run the GradleTask "gradle-no-wrapper"
    Then the executed command should start with "gradle --no-daemon build"

  Scenario: Execute a GradleTask with no tasks
    Given a GradleTask named "gradle-no-tasks" with no tasks
    When we dry-run the GradleTask "gradle-no-tasks" and expect failure
    Then the task execution should fail with IllegalArgumentException

  Scenario: Execute a GoTest task with default packages
    Given a GoTest task named "go-test-default" with default packages
    When we dry-run the GoTest task "go-test-default"
    Then the executed command should start with "go test ./..."

  Scenario: Execute a custom Go command with no command name
    When we dry-run a custom Go command with no command name
    Then the executed command should start with "go version"
