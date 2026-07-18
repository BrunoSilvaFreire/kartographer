@smoke @integration
Feature: TaskStateStore Persistence
  Verify that task execution states are correctly saved to and loaded from the persistent store.

  Scenario: Save and load task state round-trip
    Given a temporary workspace directory
    And we load task state script "examples/task_state/karto.kts"
    And the input file "in.txt" exists with content "input data"
    And the output file "out.txt" exists with content "output data"
    When we save the state of task "state-task"
    Then the task state file should exist on disk
    And we can load the task state of task "state-task"
    And the loaded task state input file "in.txt" hash should match the current hash of "in.txt"
    And the loaded task state output file "out.txt" hash should match the current hash of "out.txt"
