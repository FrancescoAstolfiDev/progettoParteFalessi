package project.controllers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import project.statefull.ConstantSize;
import project.utils.CostumException;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;


/**
 * Class dedicated to repository operations like backup and restore.
 */
public class RepositoryManager {
    private static  final Logger LOGGER = LoggerFactory.getLogger(RepositoryManager.class);
    private Git git;
    private Repository repository;
    private String originalRepoPath;
    private String backupRepoPath;
    private final GitHubInfoRetrieve gitHubInfoRetrieve;
    private static final String JAVA=".java";

    // Cache for storing excess files from commits
    private final Map<String, byte[]> cachedFiles = new HashMap<>();



    /**
     * Constructor that takes a GitHubInfoRetrieve object
     */
    public RepositoryManager(GitHubInfoRetrieve gitHubInfoRetrieve) {
        this.gitHubInfoRetrieve = gitHubInfoRetrieve;
        File repoDir = new File(gitHubInfoRetrieve.getPath());

        try {
            // Open repository and create Git instance in try-with-resources
            try (Git gitResource = Git.open(repoDir)) {
                this.repository = gitResource.getRepository();
                this.git = new Git(this.repository);  // Create new Git instance from the repository
                LOGGER.info("RepositoryManager: Repository opened successfully");
            }
        } catch (IOException e) {
            throw new CostumException("Failed to open git directory: " + repoDir.getAbsolutePath(), e);
        }
    }



    /**
     * Copies a directory and its contents to a target directory
     */
    protected void copyDirectory(Path sourceDir, Path targetDir) throws IOException {
        try (var walk = Files.walk(sourceDir)) {
            walk.forEach(source -> {
                try {
                    Path relativePath = sourceDir.relativize(source);
                    Path destination = targetDir.resolve(relativePath);

                    if (Files.isDirectory(source)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(source, destination);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Error during copy: " + source, e);
                }
            });
        }
    }

    /**
     * Creates a backup of the repository
     */
    public void backupRepository() throws IOException {
        // Verifica dei prerequisiti
        if (repository == null) {
            throw new IOException("Repository not initialized");
        }

        try {
            // Chiude le risorse esistenti
            if (git != null) {
                git.close();
            }
            repository.close();

            File gitDir = repository.getDirectory();
            originalRepoPath = gitDir.getParentFile().getAbsolutePath();
            backupRepoPath = originalRepoPath + "_backup_" + System.currentTimeMillis();

            // Crea e verifica la directory di backup
            Path backupPath = Paths.get(backupRepoPath);
            Files.createDirectories(backupPath);

            // Copia i file
            copyDirectory(Paths.get(originalRepoPath), backupPath);

            // Riapre il repository originale usando try-with-resources
            File repoDir = new File(gitHubInfoRetrieve.getPath());
            try (Git tempGit = Git.open(repoDir)) {
                this.repository = tempGit.getRepository();
                this.git = new Git(repository);
            }

        } catch (UncheckedIOException e) {
            throw new IOException("Error during backup: " + e.getMessage(), e.getCause());
        }
    }

    /**
     * Restores the repository from a backup using the class fields
     */
    public void restoreFromBackup() throws IOException {
        restoreFromBackup(this.backupRepoPath, this.originalRepoPath);
    }

    /**
     * Restores the repository from a backup with improved handling for locked files
     */
    public void restoreFromBackup(String backupRepoPath, String originalRepoPath) throws IOException {
        validatePaths(backupRepoPath, originalRepoPath);

        try {
            closeResources();
            prepareForRestore();

            Path backupPath = Paths.get(backupRepoPath);
            Path originalPath = Paths.get(originalRepoPath);

            handleOriginalDirectory(originalPath);
            performRestore(backupPath, originalPath);

        } catch (UncheckedIOException e) {
            logAndRethrowCopyError(e);
        } catch (InterruptedException e) {
            handleInterruption(e);
        }
    }

    private void validatePaths(String backupRepoPath, String originalRepoPath) throws IOException {
        if (backupRepoPath == null || originalRepoPath == null) {
            throw new IOException("No backup available for restoration");
        }

        Path backupPath = Paths.get(backupRepoPath);
        if (!Files.exists(backupPath)) {
            throw new IOException("Backup directory not found: " + backupRepoPath);
        }
    }

    private void closeResources() {
        if (git != null) {
            git.close();
            git = null;
        }
        if (repository != null) {
            repository.close();
            repository = null;
        }
    }

    private void prepareForRestore() throws InterruptedException {
        Thread.sleep(500);
    }

    private void handleOriginalDirectory(Path originalPath){
        if (!Files.exists(originalPath)) {
            return;
        }

        boolean deleted = deleteDirectoryWithRetry(originalPath, 5, 1000);
        if (!deleted) {
            LOGGER.warn("Some files were not deleted during restoration. " +
                    "The restoration will continue anyway, but there might be problems.");
        }
    }

    private void performRestore(Path backupPath, Path originalPath) throws IOException {
        Files.createDirectories(originalPath);
        copyDirectory(backupPath, originalPath);
        openAndVerifyRepository(originalPath, backupPath);
    }

    private void openAndVerifyRepository(Path originalPath, Path backupPath) throws IOException {
        try {
            openRepository(originalPath.toString());
            verifyAndCleanup(backupPath);
        } catch (IOException e) {
            handleRepositoryOpenError(e, backupPath);
        }
    }

    private void openRepository(String path) throws IOException {
        git = Git.open(new File(path));
        repository = git.getRepository();

        if (repository.getBranch() == null) {
            throw new IOException("Restored repository is not valid");
        }
    }

    private void verifyAndCleanup(Path backupPath){
        boolean backupDeleted = deleteDirectoryWithRetry(backupPath, 3, 500);
        logRestoreResult(backupDeleted, backupPath);
    }

    private void logRestoreResult(boolean backupDeleted, Path backupPath) {
        if (backupDeleted) {
            LOGGER.info("Repository restored successfully and backup deleted");
        } else {
            LOGGER.info("Repository restored successfully, but the backup was not completely deleted: {}", backupPath);
        }
    }

    private void handleRepositoryOpenError(IOException e, Path backupPath) throws IOException {
        LOGGER.error("Error opening the restored repository: {}", e.getMessage());
        if (Files.exists(backupPath)) {
            LOGGER.info("Backup kept for possible manual recovery at: {}", backupPath);
        }
        throw new IOException("Error restoring the repository", e);
    }

    private void logAndRethrowCopyError(UncheckedIOException e) throws IOException {
        LOGGER.error("Error during file copying: {}", e.getMessage());
        throw new IOException("Error during restoration", e.getCause());
    }

    private void handleInterruption(InterruptedException e) throws IOException {
        Thread.currentThread().interrupt();
        throw new IOException("Operation interrupted during restoration", e);
    }

    // Support method for cleaning temporary directories
    public void cleanupTempDirectory(Path dirPath) {
        if (Files.exists(dirPath)) {
            boolean deleted = deleteDirectoryWithRetry(dirPath, 3, 500);
            if (!deleted) {
                LOGGER.warn("Some files in the temporary directory {} were not deleted", dirPath);
            }
        }
    }

    /**
     * Deletes a directory with retry logic for handling locked files
     * @param dirPath The directory to delete
     * @param maxRetries Maximum number of retries for locked files
     * @param retryDelayMs Delay between retries in milliseconds
     * @return true if the directory was completely deleted, false otherwise
     */
    public boolean deleteDirectoryWithRetry(Path dirPath, int maxRetries, long retryDelayMs) {
        if (!Files.exists(dirPath)) {
            return true;
        }

        try {
            prepareForDeletion();
            List<Path> pathsToDelete = getPathsToDelete(dirPath);
            return deletePaths(pathsToDelete, maxRetries, retryDelayMs);
        } catch (IOException | InterruptedException e) {
            handleDeletionError(e);
            return false;
        }
    }

    private void prepareForDeletion() throws InterruptedException {
        Thread.sleep(100);
    }

    private List<Path> getPathsToDelete(Path dirPath) throws IOException {
        try (Stream<Path> walkStream = Files.walk(dirPath)) {
            return walkStream
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
    }

    private boolean deletePaths(List<Path> paths, int maxRetries, long retryDelayMs)
            throws InterruptedException {
        boolean allDeleted = true;

        for (Path path : paths) {
            if (!deletePathWithRetry(path, maxRetries, retryDelayMs)) {
                allDeleted = false;
            }
        }

        return allDeleted;
    }

    private boolean deletePathWithRetry(Path path, int maxRetries, long retryDelayMs)
            throws InterruptedException {
                for (int attempt = 0; attempt < maxRetries; attempt++) {
                    if (tryDelete( path, attempt, maxRetries, retryDelayMs)) {
                        return true;
                    }
                }

        logDeletionFailure(path, maxRetries);
        return false;
    }

    private boolean tryDelete(Path path, int attempt, int maxRetries, long retryDelayMs)
            throws InterruptedException {
        try {
            Files.delete(path);
            return true;
        } catch (IOException e) {
            if (attempt == maxRetries - 1) {
                LOGGER.warn("Eliminazione fallita di {} dopo {} tentativi: {}",
                        path, maxRetries, e.getMessage());
            } else {
                Thread.sleep(retryDelayMs);
            }
        }
        return false;
    }


    private void logDeletionFailure(Path path, int maxRetries) {
        LOGGER.warn("Impossibile eliminare il file dopo {} tentativi: {}", maxRetries, path);
    }

    private void handleDeletionError(Exception e) {
        LOGGER.error("Errore durante l'eliminazione della directory: {}", e.getMessage());
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }


    public File ensureTempDirectoryExists(Path path) {
        try {
            Files.createDirectories(path);
            return path.toFile();
        } catch (IOException e) {
            throw new CostumException("failed to create a new direcory",e);
        }
    }





    protected void checkoutRelease(RevCommit commit, Path commitTempDir, boolean processAllCommits) {
        try {
            // Prima prova un checkout pulito
            if (tryCheckout(commit, CheckoutStrategy.CLEAN)) {
                finalizeCheckoutWithNestedClasses(commitTempDir, commit, processAllCommits);
                return;
            }

            // Se fallisce, prova con stash
            if (tryCheckout(commit, CheckoutStrategy.STASH)) {
                finalizeCheckoutWithNestedClasses(commitTempDir, commit, processAllCommits);
                return;
            }

            // Ultima risorsa: elimina i file problematici
            if (tryCheckout(commit, CheckoutStrategy.DELETE_PROBLEMATIC)) {
                finalizeCheckoutWithNestedClasses(commitTempDir, commit, processAllCommits);
                return;
            }

            throw new GitAPIException("Checkout fallito dopo tutti i tentativi") {
                @Override
                public String getMessage() {
                    return "Checkout fallito dopo tutti i tentativi per il commit: " + commit.getName();
                }
            };

        } catch (GitAPIException e) {
            throw new CostumException("failed to checkout commit " + commit.getName(), e);
        }
    }
    private void finalizeCheckoutWithNestedClasses(Path commitTempDir, RevCommit commit, boolean processAllCommits) {
        ensureTempDirectoryExists(commitTempDir);
        // Modifica qui per gestire le classi annidate
        exportCodeToDirectoryWithNested(commit, commitTempDir, processAllCommits);
    }

    private void exportCodeToDirectoryWithNested(RevCommit commit, Path targetDir, boolean processAllCommits) {
        try {
            int processedFilesCount = handleCachedFile(targetDir);
            int maxFilesToProcess = ConstantSize.MAX_CLASSES_PER_COMMIT;

            if (processedFilesCount < maxFilesToProcess) {
                // Modifica per gestire le classi annidate
                processCommitFilesWithNested(commit, targetDir, processAllCommits, processedFilesCount, maxFilesToProcess);
            }

            logProcessedFilesCount(targetDir);
        } catch (Exception e) {
            LOGGER.error("Error during export of commit files: {} ", commit.getName(), e);
        }
    }

    private void processCommitFilesWithNested(RevCommit commit, Path targetDir, boolean processAllCommits,
                                              int processedFilesCount, int maxFilesToProcess) throws IOException {
        try (RevWalk revWalk = new RevWalk(repository);
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

            df.setRepository(repository);
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            RevCommit parent = commit.getParentCount() > 0 ? revWalk.parseCommit(commit.getParent(0).getId()) : null;
            List<DiffEntry> diffs = df.scan(parent == null ? null : parent.getTree(), commit.getTree());

            Map<String, Set<String>> nestedClassesMap = new HashMap<>();
            List<DiffEntry> javaFiles = filterJavaFilesWithNested(diffs, nestedClassesMap);
            LOGGER.info("Found {} Java files in commit (including nested classes)", javaFiles.size());

            processJavaFilesWithNested(javaFiles, commit, targetDir, processAllCommits,
                    processedFilesCount, maxFilesToProcess, nestedClassesMap);
        }
    }
    private List<DiffEntry> filterJavaFilesWithNested(List<DiffEntry> diffs, Map<String, Set<String>> nestedClassesMap) {
        List<DiffEntry> javaFiles = new ArrayList<>();
        for (DiffEntry entry : diffs) {
            if (entry.getChangeType() != DiffEntry.ChangeType.DELETE) {
                String path = entry.getNewPath();
                if (path.endsWith(JAVA) && !isTestFile(path)) {
                    javaFiles.add(entry);
                    // Aggiungi un set vuoto per ogni file Java per le sue classi annidate
                    nestedClassesMap.put(path, new HashSet<>());
                }
            }
        }
        return javaFiles;
    }



    private void processJavaFilesWithNested(List<DiffEntry> javaFiles, RevCommit commit, Path targetDir,
                                            boolean processAllCommits, int processedFilesCount, int maxFilesToProcess,
                                            Map<String, Set<String>> nestedClassesMap) throws IOException {
        for (DiffEntry entry : javaFiles) {
            String path = entry.getNewPath();
            byte[] content = retrieveContent(path, commit);

            if (content != null && !shouldCacheFile(processAllCommits, processedFilesCount, maxFilesToProcess)) {
                writeFileToTarget(targetDir, path, content);
                processedFilesCount++;

                processedFilesCount = processNestedClasses(path, content, nestedClassesMap,
                        processedFilesCount, maxFilesToProcess);
            } else if (content != null) {
                cachedFiles.put(path, content);
            }
        }
    }

    private boolean shouldCacheFile(boolean processAllCommits, int processedFilesCount, int maxFilesToProcess) {
        return !processAllCommits && processedFilesCount >= maxFilesToProcess;
    }


    private byte[] retrieveContent(String path, RevCommit commit) throws IOException {
        try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, commit.getTree())) {
            if (treeWalk == null) return new byte[0];
            return repository.open(treeWalk.getObjectId(0)).getBytes();
        }
    }




    private int processNestedClasses(String path, byte[] content, Map<String, Set<String>> nestedClassesMap,
                                     int processedFilesCount, int maxFilesToProcess) {
        Set<String> nestedClasses = nestedClassesMap.get(path);
        if (nestedClasses == null || nestedClasses.isEmpty()) return processedFilesCount;

        for (String nestedClass : nestedClasses) {
            if (processedFilesCount >= maxFilesToProcess) {
                cachedFiles.put(path + "$" + nestedClass, content);
                continue;
            }
            processedFilesCount++;
        }
        return processedFilesCount;
    }
    private enum CheckoutStrategy {
        CLEAN,
        STASH,
        DELETE_PROBLEMATIC
    }

    private boolean tryCheckout(RevCommit commit, CheckoutStrategy strategy) throws GitAPIException {
        try {
            resetRepository();

            switch (strategy) {
                case CLEAN -> cleanWorkingDirectory();
                case STASH -> stashChanges();
                case DELETE_PROBLEMATIC -> deleteProblematicFile();
            }

            git.checkout()
                    .setName(commit.getName())
                    .call();

            return true;
        } catch (GitAPIException e) {
            LOGGER.warn("Tentativo di checkout {} fallito per il commit {}: {}",
                    strategy, commit.getName(), e.getMessage());
            return false;
        }
    }

    private void resetRepository() throws GitAPIException {
        git.reset()
                .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                .call();
    }

    private void cleanWorkingDirectory() {
        try {
            git.clean()
                    .setCleanDirectories(true)
                    .setForce(true)
                    .call();
        } catch (GitAPIException e) {
            throw new CostumException("not handling the cleaning of all the director from the filese",e);
        }

    }

    private void stashChanges() {
        try {
            git.stashCreate().call();
        } catch (Exception e) {
            LOGGER.warn("Stash fallito, proseguo con il checkout: {}", e.getMessage());
        }
    }

    private void deleteProblematicFile() {
        Path problematicFile = repository.getWorkTree().toPath().resolve(
                "openjpa-project/src/doc/manual/ref_guide_runtime.xml");
        if (Files.exists(problematicFile)) {
            LOGGER.info("Eliminazione file problematico: {}", problematicFile);
            try {
                Files.delete(problematicFile);
            } catch (IOException e) {
                LOGGER.warn("Impossibile eliminare il file problematico: {}", e.getMessage());
            }
        }
    }





    public  int  handleCachedFile(Path targetDir) throws IOException {
        // Ensure target directory exists
        ensureTempDirectoryExists(targetDir);

        // Track how many files we've processed in this call
        int processedFilesCount = 0;
        int maxFilesToProcess = ConstantSize.MAX_CLASSES_PER_COMMIT;

        // First, use files from cache if available
        if (!cachedFiles.isEmpty()) {
            LOGGER.info("Using {} files from cache", cachedFiles.size());

            // Create a list of entries to avoid concurrent modification
            List<Map.Entry<String, byte[]>> cachedEntries = new ArrayList<>(cachedFiles.entrySet());

            // Process files from cache up to the maximum limit
            for (Map.Entry<String, byte[]> entry : cachedEntries) {
                if (processedFilesCount >= maxFilesToProcess) {
                    break;
                }

                String path = entry.getKey();
                byte[] content = entry.getValue();

                Path targetFilePath = targetDir.resolve(path);
                Files.createDirectories(targetFilePath.getParent());
                Files.write(targetFilePath, content);

                // Remove this file from cache as it's been processed
                cachedFiles.remove(path);
                processedFilesCount++;
            }
        }

        return processedFilesCount;
    }

    private void writeFileToTarget(Path targetDir, String path, byte[] content) throws IOException {
        Path targetFilePath = targetDir.resolve(path);
        Files.createDirectories(targetFilePath.getParent());
        Files.write(targetFilePath, content);
    }

    private void logProcessedFilesCount(Path targetDir) throws IOException {
        long count = 0;
        if (Files.exists(targetDir)) {
            try (Stream<Path> stream = Files.walk(targetDir)) {
                count = stream.filter(p -> p.toString().endsWith(JAVA)).count();
            }
        }
        LOGGER.info("Classes to process: {}, Remaining in cache: {}", count, cachedFiles.size());
    }

    private boolean isTestFile(String path) {
        String lowerPath = path.toLowerCase();
        return lowerPath.contains("/test/") || lowerPath.contains("test") || lowerPath.contains("mock");
    }
    public boolean getEmptyCache(){
        return cachedFiles.isEmpty();
    }

}
