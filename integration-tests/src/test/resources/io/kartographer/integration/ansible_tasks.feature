@smoke @integration
Feature: Ansible Tasks
  Verify that AnsiblePlaybookRun builds the correct ansible-playbook commands.

  Scenario: Run an Ansible playbook with inventory and extra vars
    Given we load ansible script "examples/ansible/karto.kts"
    When we dry-run the AnsiblePlaybookRun task "ans-run"
    Then the executed ansible command should be "ansible-playbook deploy.yml -i hosts.ini"

  Scenario: Run an Ansible playbook with tags, extra vars, and arguments
    Given we load ansible script "examples/ansible/karto.kts"
    When we dry-run the AnsiblePlaybookRun task "ans-run-complex"
    Then the executed ansible command should be "ansible-playbook deploy.yml -i hosts.ini --tags setup --extra-vars foo=bar --verbose"

  Scenario: Run an Ansible playbook with minimal configuration
    Given we load ansible script "examples/ansible/karto.kts"
    When we dry-run the AnsiblePlaybookRun task "ans-run-minimal"
    Then the executed ansible command should be "ansible-playbook deploy.yml"
