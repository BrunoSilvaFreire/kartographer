@smoke @integration
Feature: Execution Event Stream
  Verify that the scheduler notifies the ExecutionObserver of pipeline and task events in the correct order.

  Scenario: Execution events are published in correct order during task execution
    Given we load events script "examples/events/karto.kts"
    And an execution observer registered with the scheduler
    When we execute the pipeline target "simple-task" with the observer
    Then the observer should receive the following events in order:
      | PipelineStarted   |
      | TaskQueued        |
      | TaskStarted       |
      | TaskCompleted     |
      | PipelineCompleted |
