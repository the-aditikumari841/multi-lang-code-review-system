package com.aditi.githubreviewbot.ci;

import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class CIExecutor {

    private static final String DOCKER_IMAGE = "multi-lang-code-review-analyzer";

    public String cloneRepo(String repoUrl, String branch) {
        String dir = System.getProperty("java.io.tmpdir")
                + File.separator
                + "repo-" + System.currentTimeMillis();

        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "git",
                    "clone",
                    "--branch", branch,
                    "--single-branch",
                    repoUrl,
                    dir
            );

            builder.redirectErrorStream(true);
            Process process = builder.start();

            streamLogs(process);

            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("clone failed with exit code " + exitCode);
            }
        } catch (Exception e) {
            throw new RuntimeException("Clone failed: " + e.getMessage(), e);
        }
        return dir;
    }

    public void checkoutCommit(String repoPath, String sha) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "git", "checkout", sha
            );

            builder.directory(new File(repoPath));
            builder.redirectErrorStream(true);

            Process process = builder.start();

            streamLogs(process);

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("checkout failed with exit code " + exitCode);
            }
        } catch (Exception e) {
            throw new RuntimeException("checkout failed: " + e.getMessage(), e);
        }
    }

    public void deleteDirectory(String repoPath) {
        try {
            Path directory = Paths.get(repoPath);

            if (!Files.exists(directory))
                return;

            AtomicBoolean hasError = new AtomicBoolean(false);

            System.out.println("Starting cleanup: " + repoPath);

            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(p -> deleteWithRetry(p, hasError));
            }
            if (hasError.get()) {
                System.out.println("Cleanup completed with some failures: " + repoPath);
            } else {
                System.out.println("Cleanup completed successfully: " + repoPath);
            }

        } catch (Exception e) {
            System.out.println("Delete directory failed: " + e.getMessage());
        }
    }

    private void deleteWithRetry(Path repoPath, AtomicBoolean hasError) {
        int attempts = 3;

        while (attempts > 0) {
            try {
                Files.delete(repoPath);
                return;
            } catch (IOException e) {
                attempts--;

                if (attempts == 0) {
                    hasError.set(true);
                    System.out.println("Failed to delete: " + repoPath + " | " + e.getMessage());
                }

                try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
        }
    }

    public String runCheckStyle(String repoPath) {

        String command = "cd /workspace && " + "mvn checkstyle:check";

        return runDockerCommand(repoPath, command, "CHECKSTYLE");
    }

    public String runSpotBugs(String repoPath) {
        String command = "cd /workspace && " + "mvn spotbugs:spotbugs";

        return runDockerCommand(repoPath, command, "SPOTBUGS");
    }

    public String runRuff(String repoPath) {

        String command = "cd /workspace && " + "ruff check . --output-format json";

        return runDockerCommand(repoPath, command, "RUFF");
    }

    public String runEslint(String repoPath) {

        File configFile = new File(repoPath, "eslint.config.mjs");

        if(!configFile.exists()) {
            System.out.println("No ESLint config file found. Skipping... " + repoPath);
            return "[]";
        }

        String command =
                "cd /workspace && " +
                "npm install eslint @eslint/js globals --ignore-scripts && " +
                "npx eslint . -f json";

        return runDockerCommand(repoPath, command, "ESLINT");
    }

    private String runDockerCommand(
            String repoPath,
            String command,
            String toolName
    ) {
        try {

            System.out.println("Running " + toolName + " inside Docker...");
            System.out.println("Repository path: " + repoPath);

            ProcessBuilder builder = new ProcessBuilder(
                    "docker",
                    "run",
                    "--rm",
                    "--network=none",
                    "--memory=512m",
                    "--cpus=1",
                    "-v",
                    repoPath.replace("\\", "/") + ":/workspace",
                    DOCKER_IMAGE,
                    "bash",
                    "-c",
                    command
            );

            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException(toolName + " timed out.");
            }

            int exitCode = process.exitValue();

            System.out.println("==== " + toolName + " OUTPUT ====");
            System.out.println(output);

            System.out.println(toolName + " finished with exit code " + exitCode);
            return output.toString();

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(toolName + " execution failed: " + e.getMessage(), e);
        }
    }

    private void streamLogs(Process process) throws IOException {

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }
    }
}
