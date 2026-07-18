@smoke @integration
Feature: ExecSpec and BashSpec Validation
  Verify command escaping, environment variable merging, and elevation requirements.

  Scenario: Command arguments containing spaces and quotes are correctly escaped
    Given we load escaping script "examples/escaping/karto.kts"
    And we configure task "escape-test" with args:
      | hello world     |
      | "nested quotes" |
      | 'single quotes' |
    When we dry-run the custom task "escape-test"
    Then the executed command line should be:
      """
      echo 'hello world' '"nested quotes"' ''\''single quotes'\'''
      """

  Scenario: Environment variables from task and context are merged correctly
    Given the execution context env has "GLOBAL_VAR" set to "global"
    And we load escaping script "examples/escaping/karto.kts"
    And we configure task "env-test" with env "TASK_VAR" set to "task"
    When we dry-run the custom task "env-test"
    Then the command execution environment should contain "GLOBAL_VAR" with "global"
    And the command execution environment should contain "TASK_VAR" with "task"

  Scenario: Commands requiring elevation set the elevation flag
    Given we load escaping script "examples/escaping/karto.kts"
    And we configure task "elevated-task" to require elevation
    When we dry-run the custom task "elevated-task"
    Then the task execution should request elevation

  Scenario: Running a custom task with no action defined succeeds
    Given we load escaping script "examples/escaping/karto.kts"
    And we do not configure any action for task "escape-test"
    When we dry-run the custom task "escape-test"
    Then the task execution should succeed

  Scenario: Running a custom task that throws an error fails
    Given we load escaping script "examples/escaping/karto.kts"
    And we configure task "escape-test" to throw an exception
    When we dry-run the custom task "escape-test"
    Then the custom task execution should fail with RuntimeException
