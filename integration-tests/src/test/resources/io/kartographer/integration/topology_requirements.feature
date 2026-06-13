Feature: Topology Requirements and Constraints
  Verify that the scheduler respects task topology requirements and skips or fails
  tasks depending on worker capabilities/labels.

  Scenario: Run a task requiring a capability that the worker has
    Given a worker with capabilities "go, docker"
    And a task named "GoBuildTask" requiring capability "go"
    When we execute the pipeline target "GoBuildTask" with the worker
    Then the task "GoBuildTask" should be executed successfully

  Scenario: Fail a task requiring a capability that the worker lacks
    Given a worker with capabilities "docker"
    And a task named "GoBuildTask" requiring capability "go"
    When we execute the pipeline target "GoBuildTask" with the worker
    Then the pipeline execution should fail
    And the task "GoBuildTask" should report a capability failure

  Scenario: Run a task requiring a label that the worker has
    Given a worker with labels "os:linux, arch:amd64"
    And a task named "LinuxTask" requiring label "os" with value "linux"
    When we execute the pipeline target "LinuxTask" with the worker
    Then the task "LinuxTask" should be executed successfully

  Scenario: Fail a task requiring a label that the worker has a different value for
    Given a worker with labels "os:macos, arch:amd64"
    And a task named "LinuxTask" requiring label "os" with value "linux"
    When we execute the pipeline target "LinuxTask" with the worker
    Then the pipeline execution should fail
