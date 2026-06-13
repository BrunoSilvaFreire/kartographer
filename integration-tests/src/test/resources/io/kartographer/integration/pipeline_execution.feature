Feature: Pipeline Execution and Scheduling
  Verify that the scheduler can resolve task dependencies, run tasks in parallel,
  and handle successful and failed executions.

  Scenario: Run a task with transitive dependencies
    Given a task named "TaskA" that performs no-op
    And a task named "TaskB" that depends on "TaskA"
    And a task named "TaskC" that depends on "TaskB"
    When we execute the pipeline target "TaskC"
    Then the task "TaskA" should be executed before "TaskB"
    And the task "TaskB" should be executed before "TaskC"
    And the pipeline execution should succeed

  Scenario: Run independent tasks in parallel
    Given a task named "TaskLong1" that takes 100 milliseconds
    And a task named "TaskLong2" that takes 100 milliseconds
    And a task named "TaskFinal" that depends on "TaskLong1" and "TaskLong2"
    When we execute the pipeline target "TaskFinal"
    Then the execution should take less than 180 milliseconds
    And all tasks should complete successfully

  Scenario: Fail the pipeline when a task fails
    Given a task named "GoodTask" that performs no-op
    And a task named "FailingTask" that fails with an error
    And a task named "TargetTask" that depends on "GoodTask" and "FailingTask"
    When we execute the pipeline target "TargetTask"
    Then the pipeline execution should fail
    And the task "FailingTask" should report a failure
