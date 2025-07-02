package com.redhat.podmortem.operator.service;

import com.redhat.podmortem.common.model.kube.patternlibrary.PatternRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class PatternSyncService {

    private static final Logger log = LoggerFactory.getLogger(PatternSyncService.class);
    private static final String PATTERN_CACHE_DIR = "/tmp/pattern-cache";

    public void syncRepository(String libraryName, PatternRepository repo, String credentials) {
        try {
            log.info("Syncing repository {} for library {}", repo.getName(), libraryName);

            // Create cache directory structure
            Path libraryPath = Paths.get(PATTERN_CACHE_DIR, libraryName);
            Files.createDirectories(libraryPath);

            Path repoPath = libraryPath.resolve(repo.getName());

            if (Files.exists(repoPath)) {
                // Pull latest changes
                pullRepository(repoPath, repo, credentials);
            } else {
                // Clone repository
                cloneRepository(repoPath, repo, credentials);
            }

            // Validate patterns in the repository
            validatePatterns(repoPath);

            log.info(
                    "Successfully synced repository {} for library {}",
                    repo.getName(),
                    libraryName);

        } catch (Exception e) {
            log.error(
                    "Failed to sync repository {} for library {}: {}",
                    repo.getName(),
                    libraryName,
                    e.getMessage(),
                    e);
            throw new RuntimeException("Repository sync failed", e);
        }
    }

    public List<String> getAvailableLibraries(String libraryName) {
        List<String> libraries = new ArrayList<>();
        try {
            Path libraryPath = Paths.get(PATTERN_CACHE_DIR, libraryName);
            if (Files.exists(libraryPath)) {
                // Scan for pattern library directories and files
                try (Stream<Path> paths = Files.walk(libraryPath, 2)) {
                    paths.filter(Files::isDirectory)
                            .filter(path -> !path.equals(libraryPath))
                            .map(path -> path.getFileName().toString())
                            .forEach(libraries::add);
                }

                // Also scan for YAML files that might be libraries
                try (Stream<Path> yamlFiles = Files.walk(libraryPath)) {
                    yamlFiles
                            .filter(Files::isRegularFile)
                            .filter(
                                    path ->
                                            path.toString().endsWith(".yaml")
                                                    || path.toString().endsWith(".yml"))
                            .map(
                                    path ->
                                            path.getFileName()
                                                    .toString()
                                                    .replaceAll("\\.(yaml|yml)$", ""))
                            .forEach(libraries::add);
                }
            }
        } catch (Exception e) {
            log.error(
                    "Failed to get available libraries for {}: {}", libraryName, e.getMessage(), e);
        }
        return libraries;
    }

    private void cloneRepository(Path repoPath, PatternRepository repo, String credentials) {
        try {
            log.info("Cloning repository {} to {}", repo.getUrl(), repoPath);

            // For now, using simple git command execution
            // In a full implementation, you'd use JGit library
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(repoPath.getParent().toFile());

            if (credentials != null) {
                // Handle authentication - this is simplified
                String authUrl = repo.getUrl().replace("https://", "https://" + credentials + "@");
                pb.command(
                        "git",
                        "clone",
                        "-b",
                        repo.getBranch() != null ? repo.getBranch() : "main",
                        authUrl,
                        repoPath.getFileName().toString());
            } else {
                pb.command(
                        "git",
                        "clone",
                        "-b",
                        repo.getBranch() != null ? repo.getBranch() : "main",
                        repo.getUrl(),
                        repoPath.getFileName().toString());
            }

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("Git clone failed with exit code: " + exitCode);
            }

        } catch (Exception e) {
            log.error("Failed to clone repository {}: {}", repo.getUrl(), e.getMessage(), e);
            throw new RuntimeException("Git clone failed", e);
        }
    }

    private void pullRepository(Path repoPath, PatternRepository repo, String credentials) {
        try {
            log.info("Pulling latest changes for repository at {}", repoPath);

            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(repoPath.toFile());
            pb.command(
                    "git", "pull", "origin", repo.getBranch() != null ? repo.getBranch() : "main");

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("Git pull failed with exit code: " + exitCode);
            }

        } catch (Exception e) {
            log.error("Failed to pull repository at {}: {}", repoPath, e.getMessage(), e);
            throw new RuntimeException("Git pull failed", e);
        }
    }

    private void validatePatterns(Path repoPath) {
        try {
            // Basic validation - check if YAML files are present
            try (Stream<Path> files = Files.walk(repoPath)) {
                long yamlCount =
                        files.filter(Files::isRegularFile)
                                .filter(
                                        path ->
                                                path.toString().endsWith(".yaml")
                                                        || path.toString().endsWith(".yml"))
                                .count();

                if (yamlCount == 0) {
                    log.warn("No YAML pattern files found in repository at {}", repoPath);
                }

                log.info("Found {} YAML pattern files in repository at {}", yamlCount, repoPath);
            }
        } catch (Exception e) {
            log.error(
                    "Failed to validate patterns in repository at {}: {}",
                    repoPath,
                    e.getMessage(),
                    e);
        }
    }
}
