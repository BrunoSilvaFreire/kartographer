Feature: Local Plugin Dynamic Compilation and Reloading
  Verify that compiling and reloading local plugin tasks works dynamically
  and updates property changes immediately when running pipeline scripts.

  Scenario: Loading and modifying a custom task in a local plugin
    Given a temporary project directory
    And a custom local plugin with a task "CustomSampleTask" that has property "sampleRate" defaulting to 10
    When we load and execute the pipeline script "pipeline.kts" defining:
      """
      import io.kartographer.tasks.custom.*
      task<CustomSampleTask>("sample") {}
      """
    Then the task "sample" should have property "sampleRate" with value 10

    When we modify the custom local plugin task "CustomSampleTask" property "sampleRate" default value to 20
    And we load and execute the pipeline script "pipeline.kts" again
    Then the task "sample" should have property "sampleRate" with value 20
