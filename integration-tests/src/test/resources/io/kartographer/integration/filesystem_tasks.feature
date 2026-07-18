@smoke @integration
Feature: Filesystem Tasks
  Verify that Copy, Sync, Move, and Delete tasks perform correct filesystem operations.

  Scenario: Copy files from source to destination
    Given a temporary directory
    And a file "src/a.txt" with content "hello"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "copy-task"
    Then the file "dest/a.txt" should exist with content "hello"

  Scenario: Copy directory recursively
    Given a temporary directory
    And a file "src_dir/a.txt" with content "hello"
    And a file "src_dir/sub/b.txt" with content "world"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "copy-dir"
    Then the file "dest_dir/a.txt" should exist with content "hello"
    And the file "dest_dir/sub/b.txt" should exist with content "world"

  Scenario: Copy directory with filters
    Given a temporary directory
    And a file "src_dir/a.txt" with content "hello"
    And a file "src_dir/b.conf" with content "config"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "copy-filtered"
    Then the file "dest_dir_filtered/a.txt" should exist with content "hello"
    And the file "dest_dir_filtered/b.conf" should not exist

  Scenario: Copy fails without destination
    Given a temporary directory
    And a file "src/a.txt" with content "hello"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "copy-no-dest"
    Then the filesystem task execution should fail with IllegalArgumentException

  Scenario: Copy fails with nonexistent source
    Given a temporary directory
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "copy-no-source"
    Then the filesystem task execution should fail with IllegalArgumentException

  Scenario: Sync files and delete orphaned files in destination
    Given a temporary directory
    And a file "src/a.txt" with content "hello"
    And a file "dest/orphaned.txt" with content "old"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "sync-task"
    Then the file "dest/a.txt" should exist with content "hello"
    And the file "dest/orphaned.txt" should not exist

  Scenario: Sync directory recursively
    Given a temporary directory
    And a file "src_dir/a.txt" with content "hello"
    And a file "dest_dir/orphaned.txt" with content "old"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "sync-dir"
    Then the file "dest_dir/a.txt" should exist with content "hello"
    And the file "dest_dir/orphaned.txt" should not exist

  Scenario: Sync directory with filters
    Given a temporary directory
    And a file "src_dir/a.txt" with content "hello"
    And a file "src_dir/b.conf" with content "config"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "sync-filtered"
    Then the file "dest_dir_sync_filtered/a.txt" should exist with content "hello"
    And the file "dest_dir_sync_filtered/b.conf" should not exist

  Scenario: Sync fails without destination
    Given a temporary directory
    And a file "src/a.txt" with content "hello"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "sync-no-dest"
    Then the filesystem task execution should fail with IllegalArgumentException

  Scenario: Sync fails with nonexistent source
    Given a temporary directory
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "sync-no-source"
    Then the filesystem task execution should fail with IllegalArgumentException

  Scenario: Move files from source to destination
    Given a temporary directory
    And a file "src/a.txt" with content "hello"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "move-task"
    Then the file "dest/a.txt" should exist with content "hello"
    And the file "src/a.txt" should not exist

  Scenario: Move directory recursively
    Given a temporary directory
    And a file "src_dir/a.txt" with content "hello"
    And a file "src_dir/sub/b.txt" with content "world"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "move-dir"
    Then the file "dest_dir/a.txt" should exist with content "hello"
    And the file "dest_dir/sub/b.txt" should exist with content "world"
    And the file "src_dir/a.txt" should not exist
    And the file "src_dir/sub/b.txt" should not exist

  Scenario: Move fails without destination
    Given a temporary directory
    And a file "src/a.txt" with content "hello"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "move-no-dest"
    Then the filesystem task execution should fail with IllegalArgumentException

  Scenario: Move fails with nonexistent source
    Given a temporary directory
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "move-no-source"
    Then the filesystem task execution should fail with IllegalArgumentException

  Scenario: Delete files and directories
    Given a temporary directory
    And a file "src/a.txt" with content "hello"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "delete-task"
    Then the file "src/a.txt" should not exist

  Scenario: Delete directory recursively
    Given a temporary directory
    And a file "src_dir/a.txt" with content "hello"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "delete-dir"
    Then the file "src_dir/a.txt" should not exist

  Scenario: Delete non-existent file or directory does not fail
    Given a temporary directory
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "delete-nonexistent"
    Then the filesystem pipeline execution should succeed

  Scenario: Copy fails on I/O exception
    Given a temporary directory
    And a file "src/a.txt" with content "hello"
    And a write-protected directory "dest"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "copy-task"
    Then the filesystem task execution should fail

  Scenario: Sync fails on I/O exception
    Given a temporary directory
    And a file "src/a.txt" with content "hello"
    And a write-protected directory "dest"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "sync-task"
    Then the filesystem task execution should fail

  Scenario: Move fails on I/O exception
    Given a temporary directory
    And a file "src/a.txt" with content "hello"
    And a write-protected directory "dest"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "move-task"
    Then the filesystem task execution should fail

  Scenario: Delete fails on I/O exception
    Given a temporary directory
    And a file "src/a.txt" with content "hello"
    And a write-protected directory "src"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "delete-task"
    Then the filesystem task execution should fail

  Scenario: Copy single file with filter that does not match skips it
    Given a temporary directory
    And a file "src/b.conf" with content "config"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "copy-file-filtered-skip"
    Then the file "dest_skip/b.conf" should not exist

  Scenario: Sync single file with filter that does not match skips it
    Given a temporary directory
    And a file "src/b.conf" with content "config"
    And we load filesystem script "examples/filesystem/karto.kts"
    When we execute the filesystem task "sync-file-filtered-skip"
    Then the file "dest_sync_skip/b.conf" should not exist
