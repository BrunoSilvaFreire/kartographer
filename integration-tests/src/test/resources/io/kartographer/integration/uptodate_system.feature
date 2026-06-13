Feature: Flexible Up-To-Date System
  Verify that the up-to-date checking system skips tasks when inputs, outputs,
  or properties haven't changed, and executes them when changes are detected.

  Scenario: Task is skipped on second run if inputs and outputs are unchanged
    Given a temporary test directory
    And an input file "in.txt" with content "source data"
    And a task named "hash-task" that copies "in.txt" to "out.txt"
    And the task "hash-task" defines "in.txt" as input and "out.txt" as output
    When we execute the pipeline target "hash-task"
    Then the task "hash-task" should be executed
    When we execute the pipeline target "hash-task" again
    Then the task "hash-task" should be skipped as up-to-date

  Scenario: Task re-runs if an input file is modified
    Given a temporary test directory
    And an input file "in.txt" with content "source data"
    And a task named "hash-task" that copies "in.txt" to "out.txt"
    And the task "hash-task" defines "in.txt" as input and "out.txt" as output
    When we execute the pipeline target "hash-task"
    Then the task "hash-task" should be executed
    When we modify the input file "in.txt" to "new source data"
    And we execute the pipeline target "hash-task" again
    Then the task "hash-task" should be executed

  Scenario: Task is skipped if inputs are older than outputs (timestamp check)
    Given a temporary test directory
    And an input file "in.txt" with content "in"
    And an output file "out.txt" with content "out"
    And a task named "timestamp-task" that copy-syncs "in.txt" to "out.txt"
    And the task "timestamp-task" has input "in.txt" and output "out.txt"
    And the task "timestamp-task" uses the inputs newer than outputs check
    When the input file "in.txt" modification time is set to 1000
    And the output file "out.txt" modification time is set to 2000
    And we execute the pipeline target "timestamp-task"
    Then the task "timestamp-task" should be skipped as up-to-date

  Scenario: Task runs if a tracked custom property changes
    Given a temporary test directory
    And a task named "prop-task" that tracks a custom property "version" with value "1.0"
    When we execute the pipeline target "prop-task"
    Then the task "prop-task" should be executed
    When we execute the pipeline target "prop-task" again
    Then the task "prop-task" should be skipped as up-to-date
    When we change the tracked custom property "version" value to "2.0"
    And we execute the pipeline target "prop-task" again
    Then the task "prop-task" should be executed
