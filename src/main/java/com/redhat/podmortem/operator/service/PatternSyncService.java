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

/**
 * Service for synchronizing pattern definitions from external Git repositories.
 *
 * <p>Manages Git clone and pull operations for pattern repositories, handles authentication
 * credentials, validates pattern files, and provides discovery of available pattern libraries. The
 * service maintains a shared cache directory that can be accessed by log parser services.
 */
@ApplicationScoped
public class PatternSyncService {

    private static final Logger log = LoggerFactory.getLogger(PatternSyncService.class);
    private static final String PATTERN_CACHE_DIR = "/shared/patterns"; // shared with log-parser

    /**
     * Synchronizes a pattern repository for a specific pattern library.
     *
     * <p>Performs either a clone or pull operation depending on whether the repository already
     * exists locally. Validates the synchronized patterns and maintains a directory structure
     * organized by library name and repository.
     *
     * @param libraryName the name of the pattern library requesting this sync
     * @param repo the repository configuration containing URL, branch, and credentials
     * @param credentials optional authentication credentials (username:password or token)
     * @throws RuntimeException if the Git operation fails
     */
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

    /**
     * Discovers available pattern libraries by scanning YAML files in the cache.
     *
     * <p>Scans the library's cache directory for YAML/YML files and returns a list of discovered
     * pattern library names (filename without extension).
     *
     * @param libraryName the pattern library name to scan for available patterns
     * @return a list of discovered pattern library names
     */
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

    /**
     * Clones a Git repository to the local cache directory.
     *
     * <p>Performs initial clone of a repository with support for branch selection and
     * authentication credentials. Supports both username:password and token-based authentication
     * for HTTPS repositories.
     *
     * @param repoPath the local path where the repository should be cloned
     * @param repo the repository configuration containing URL and branch information
     * @param credentials optional authentication credentials in "username:password" or token format
     * @throws RuntimeException if the Git clone operation fails
     */
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

    /**
     * Pulls the latest changes from a Git repository.
     *
     * <p>Updates an existing repository with the latest changes from the remote. Supports the same
     * authentication methods as cloning operations.
     *
     * @param repoPath the local path of the existing repository
     * @param repo the repository configuration for authentication
     * @param credentials optional authentication credentials
     * @throws RuntimeException if the Git pull operation fails
     */
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

    /**
     * Validates that a repository contains expected pattern files.
     *
     * <p>Performs basic validation by counting YAML files in the repository and logging warnings if
     * no pattern files are found. This helps identify repositories that may be misconfigured or
     * missing pattern definitions.
     *
     * @param repoPath the path to the repository to validate
     */
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
