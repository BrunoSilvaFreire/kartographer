@smoke @integration
Feature: Container Tasks
  Verify that ContainerBuild and ContainerPush build the correct Docker/Nerdctl commands.

  Scenario: Build a container image using Docker
    Given we load container script "examples/container/karto.kts"
    When we dry-run the ContainerBuild task "docker-build"
    Then the executed container command should be "docker build -t my-app -f Dockerfile ."

  Scenario: Push a container image using Docker
    Given we load container script "examples/container/karto.kts"
    When we dry-run the ContainerBuild task "docker-build-versioned"
    And we dry-run the ContainerPush task "docker-push"
    Then the executed container commands should be:
      | docker build -t my-app:1.0 -f Dockerfile --build-arg VERSION=1.0 . |
      | docker tag my-app:1.0 my-reg.com/my-app:1.0 |
      | docker push my-reg.com/my-app:1.0           |

  Scenario: Build a container image with platform, version and rootful client
    Given we load container script "examples/container/karto.kts"
    When we dry-run the ContainerBuild task "container-build-full"
    Then the executed container command should be "sudo nerdctl build -t my-app:2.0.0 -f Dockerfile --build-arg VERSION=2.0.0 --platform linux/amd64 ."
    And the task "container-build-full" should require elevation
    And the task "docker-build" should not require elevation

  Scenario: Push a container image with rootful client
    Given we load container script "examples/container/karto.kts"
    When we dry-run the ContainerPush task "container-push-rootful"
    Then the executed container commands should be:
      | sudo nerdctl tag my-app:1.0 my-reg.com/my-app:1.0 |
      | sudo nerdctl push --all-platforms my-reg.com/my-app:1.0 |
    And the task "container-push-rootful" should require elevation
    And the task "docker-push" should not require elevation

  Scenario: Push a container image failure during tagging
    Given we load container script "examples/container/karto.kts"
    And we configure execution environment to fail on next commands
    When we dry-run the ContainerBuild task "docker-build-versioned"
    And we dry-run the ContainerPush task "docker-push" and expect failure
    Then the execution result should be failure
