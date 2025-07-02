package com.redhat.podmortem.operator.service;

import com.redhat.podmortem.common.model.kube.patternlibrary.PatternRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class PatternSyncService {

    private static final Logger log = LoggerFactory.getLogger(PatternSyncService.class);
    private static final String PATTERN_CACHE_DIR = "/shared/patterns"; // Shared with log-parser

    public void syncRepository(String libraryName, PatternRepository repo, String credentials) {
        try {
            log.info("Syncing repository {} for library {}", repo.getName(), libraryName);

            // create cache directory structure
            Path libraryPath = Paths.get(PATTERN_CACHE_DIR, libraryName);
            Files.createDirectories(libraryPath);

            Path repoPath = libraryPath.resolve(repo.getName());

            if (Files.exists(repoPath)) {
                // pull latest changes
                pullRepository(repoPath, repo, credentials);
            } else {
                // clone repository
                cloneRepository(repoPath, repo, credentials);
            }

            // validate patterns in the repository
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
                // scan for YAML files that are pattern libraries
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

            String branch = repo.getBranch() != null ? repo.getBranch() : "main";

            var cloneCommand =
                    Git.cloneRepository()
                            .setURI(repo.getUrl())
                            .setDirectory(repoPath.toFile())
                            .setBranch(branch);

            // add credentials if provided (HTTPS only, no SSH)
            if (credentials != null && !credentials.trim().isEmpty()) {
                String[] parts = credentials.split(":", 2);
                if (parts.length == 2) {
                    cloneCommand.setCredentialsProvider(
                            new UsernamePasswordCredentialsProvider(parts[0], parts[1]));
                } else {
                    // token-only authentication (GitHub personal access token)
                    cloneCommand.setCredentialsProvider(
                            new UsernamePasswordCredentialsProvider("", credentials));
                }
                log.debug("Using HTTPS credentials for repository cloning");
            }

            try (Git git = cloneCommand.call()) {
                log.info("Successfully cloned repository {} to {}", repo.getUrl(), repoPath);
            }

        } catch (GitAPIException e) {
            log.error("Failed to clone repository {}: {}", repo.getUrl(), e.getMessage(), e);
            throw new RuntimeException("Git clone failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to clone repository {}: {}", repo.getUrl(), e.getMessage(), e);
            throw new RuntimeException("Git clone failed", e);
        }
    }

    private void pullRepository(Path repoPath, PatternRepository repo, String credentials) {
        try {
            log.info("Pulling latest changes for repository at {}", repoPath);

            try (Git git = Git.open(repoPath.toFile())) {
                var pullCommand = git.pull();

                // add credentials if provided
                if (credentials != null && !credentials.trim().isEmpty()) {
                    String[] parts = credentials.split(":", 2);
                    if (parts.length == 2) {
                        pullCommand.setCredentialsProvider(
                                new UsernamePasswordCredentialsProvider(parts[0], parts[1]));
                    } else {
                        pullCommand.setCredentialsProvider(
                                new UsernamePasswordCredentialsProvider("", credentials));
                    }
                    log.debug("Using HTTPS credentials for repository pull");
                }

                var result = pullCommand.call();

                if (result.isSuccessful()) {
                    log.info("Successfully pulled latest changes for repository at {}", repoPath);
                } else {
                    log.warn(
                            "Pull completed with issues for repository at {}: {}",
                            repoPath,
                            result.getMergeResult());
                }
            }

        } catch (GitAPIException e) {
            log.error("Failed to pull repository at {}: {}", repoPath, e.getMessage(), e);
            throw new RuntimeException("Git pull failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to pull repository at {}: {}", repoPath, e.getMessage(), e);
            throw new RuntimeException("Git pull failed", e);
        }
    }

    private void validatePatterns(Path repoPath) {
        try {
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
                } else {
                    log.info(
                            "Found {} YAML pattern files in repository at {}", yamlCount, repoPath);
                }
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
