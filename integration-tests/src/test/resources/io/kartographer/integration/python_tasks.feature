@smoke @integration
Feature: Python Tasks
  Verify that PythonExecTask builds the correct command lines.

  Scenario: Run a Python script via scriptPath with custom arguments
    Given we load python script "examples/python/karto.kts"
    And we set the python arguments to "arg1, arg2"
    When we dry-run the PythonExecTask "py-script"
    Then the executed python command should start with "python3 scripts/run.py arg1 arg2"

  Scenario: Run a Python module using venv
    Given we load python script "examples/python/karto.kts"
    And we set the python arguments to "-v"
    When we dry-run the PythonExecTask "py-module"
    Then the executed python command should start with ".venv/bin/python -m pytest -v"

  Scenario: Run a Python script with environment and argument setters
    Given we load python script "examples/python/karto.kts"
    When we dry-run the PythonExecTask "py-complex"
    Then the executed python command should start with "python3 scripts/run.py arg1 arg2"

  Scenario: Run an empty Python task
    Given we load python script "examples/python/karto.kts"
    When we dry-run the PythonExecTask "py-empty"
    Then the executed python command should start with "python3"
