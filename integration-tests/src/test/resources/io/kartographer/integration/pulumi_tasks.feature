@smoke @integration
Feature: Pulumi Tasks
  Verify that Pulumi tasks build the correct preview, up, and destroy commands.

  Scenario: Run a Pulumi up command on a stack
    Given we load pulumi script "examples/pulumi/karto.kts"
    When we dry-run the PulumiTask "pl-up"
    Then the executed pulumi command should be "pulumi up --stack dev --cwd infra/"

  Scenario: Run a Pulumi default command with arguments
    Given we load pulumi script "examples/pulumi/karto.kts"
    When we dry-run the PulumiTask "pl-default"
    Then the executed pulumi command should be "pulumi preview --yes"

  Scenario: Run a Pulumi preview command on a stack
    Given we load pulumi script "examples/pulumi/karto.kts"
    When we dry-run the PulumiTask "pl-preview"
    Then the executed pulumi command should be "pulumi preview --stack dev"

  Scenario: Run a Pulumi refresh command on a stack
    Given we load pulumi script "examples/pulumi/karto.kts"
    When we dry-run the PulumiTask "pl-refresh"
    Then the executed pulumi command should be "pulumi refresh --stack dev"

  Scenario: Run a Pulumi destroy command on a stack
    Given we load pulumi script "examples/pulumi/karto.kts"
    When we dry-run the PulumiTask "pl-destroy"
    Then the executed pulumi command should be "pulumi destroy --stack dev"
