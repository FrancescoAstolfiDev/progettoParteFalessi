package project.controllers;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import project.utils.ConstantSize;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Class dedicated to repository operations like backup and restore.
 */
public class RepositoryManager {
    private  final Logger LOGGER = LoggerFactory.getLogger(RepositoryManager.class);
    private Git git;
    private Repository repository;
    private String originalRepoPath;
    private String backupRepoPath;
    private final GitHubInfoRetrieve gitHubInfoRetrieve;

    // Cache for storing excess files from commits
    private final Map<String, byte[]> cachedFiles = new HashMap<>();

    /**
     * Constructor that takes a GitHubInfoRetrieve object
     */
    public RepositoryManager(GitHubInfoRetrieve gitHubInfoRetrieve) {


        try {
            this.gitHubInfoRetrieve = gitHubInfoRetrieve;
            File repoDir = new File(gitHubInfoRetrieve.getPath());
            this.repository = Git.open(repoDir).getRepository();
            this.git = new Git(repository);
        } catch (IOException e) {
            throw new ProcessingInterruptedException("no opening of the git directory", e);
        }finally{
            LOGGER.info("RepositoryManager: Repository opened successfully");
        }

    }



    /**
     * Gets the original repository path
     */
    public String getOriginalRepoPath() {
        return originalRepoPath;
    }

    /**
     * Gets the backup repository path
     */
    public String getBackupRepoPath() {
        return backupRepoPath;
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
                    throw new UncheckedIOException("Errore durante la copia: " + source, e);
                }
            });
        }
    }

    /**
     * Creates a backup of the repository
     */
    public void backupRepository() throws IOException {
        // Verifica prerequisiti
        if (repository == null) {
            throw new IOException("Repository non inizializzato");
        }

        try {
            // Chiudi le risorse esistenti
            if (git != null) {
                git.close();
            }
            repository.close();

            File gitDir = repository.getDirectory();
            originalRepoPath = gitDir.getParentFile().getAbsolutePath();
            backupRepoPath = originalRepoPath + "_backup_" + System.currentTimeMillis();

            // Crea e verifica directory di backup
            Path backupPath = Paths.get(backupRepoPath);
            Files.createDirectories(backupPath);

            // Copia i file
            copyDirectory(Paths.get(originalRepoPath), backupPath);

            // Riapri il repository originale
            File repoDir = new File(gitHubInfoRetrieve.getPath());
            this.repository = Git.open(repoDir).getRepository();
            this.git = new Git(repository);

        } catch (UncheckedIOException e) {
            throw new IOException("Errore durante il backup: " + e.getMessage(), e.getCause());
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
        // Verifica prerequisiti
        if (backupRepoPath == null || originalRepoPath == null) {
            throw new IOException("Nessun backup disponibile per il ripristino");
        }

        Path backupPath = Paths.get(backupRepoPath);
        if (!Files.exists(backupPath)) {
            throw new IOException("Directory di backup non trovata: " + backupRepoPath);
        }

        try {
            // Chiudi il repository corrente e rilascia tutte le risorse
            if (git != null) {
                git.close();
                git = null;
            }
            if (repository != null) {
                repository.close();
                repository = null;
            }

            // Forza la garbage collection per liberare risorse
            System.gc();
            Thread.sleep(500); // Attendi che la GC faccia effetto

            // Elimina la directory corrente con retry
            Path originalPath = Paths.get(originalRepoPath);
            if (Files.exists(originalPath)) {
                boolean deleted = deleteDirectoryWithRetry(originalPath, 5, 1000);
                if (!deleted) {
                    LOGGER.warn("Alcuni file non sono stati eliminati durante il ripristino. " +
                               "Il ripristino continuerà comunque, ma potrebbero esserci problemi.");
                }
            }

            // Crea la directory originale
            Files.createDirectories(originalPath);

            // Copia i file dal backup
            copyDirectory(backupPath, originalPath);

            // Tenta di aprire il nuovo repository
            try {
                git = Git.open(new File(originalRepoPath));
                repository = git.getRepository();

                // Verifica che il repository sia valido
                if (repository.getBranch() != null) {
                    // Solo se tutto è ok, elimina il backup con retry
                    boolean backupDeleted = deleteDirectoryWithRetry(backupPath, 3, 500);
                    if (backupDeleted) {
                        LOGGER.info("Repository ripristinato con successo e backup eliminato");
                    } else {
                        LOGGER.info("Repository ripristinato con successo, ma il backup non è stato completamente eliminato: {}", backupPath);
                    }
                } else {
                    throw new IOException("Repository ripristinato non valido");
                }
            } catch (IOException e) {
                LOGGER.error("Errore nell'apertura del repository ripristinato: {}", e.getMessage());
                if (Files.exists(backupPath)) {
                    LOGGER.info("Backup mantenuto per possibile recupero manuale in: {}", backupPath);
                }
                throw new IOException("Errore nel ripristino del repository", e);
            }

        } catch (UncheckedIOException e) {
            LOGGER.error("Errore durante la copia dei file: {}", e.getMessage());
            throw new IOException("Errore durante il ripristino", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Operazione interrotta durante il ripristino", e);
        }
    }

    // Metodo di supporto per pulire le directory temporanee
    public void cleanupTempDirectory(Path dirPath) {
        if (Files.exists(dirPath)) {
            boolean deleted = deleteDirectoryWithRetry(dirPath, 3, 500);
            if (!deleted) {
                LOGGER.warn("Alcuni file nella directory temporanea {} non sono stati eliminati", dirPath);
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
            return true; // Directory doesn't exist, consider it deleted
        }

        boolean allDeleted = true;

        try {
            // First attempt to release resources by calling System.gc()
            System.gc();
            Thread.sleep(100); // Give GC a moment to work

            // Get all files in reverse order (leaves first, then directories)
            List<Path> pathsToDelete = Files.walk(dirPath)
                    .sorted(Comparator.reverseOrder())
                    .toList();

            // Try to delete each file/directory
            for (Path path : pathsToDelete) {
                boolean deleted = false;
                File file = path.toFile();

                // Try multiple times for each file
                for (int attempt = 0; attempt < maxRetries && !deleted; attempt++) {
                    try {
                        deleted = file.delete();
                        if (!deleted) {
                            // If not deleted, wait before retry
                            if (attempt < maxRetries - 1) {
                                Thread.sleep(retryDelayMs);
                                System.gc(); // Try to release file handles
                            }
                        }
                    } catch (Exception e) {
                        if (attempt == maxRetries - 1) {
                            LOGGER.warn("Failed to delete {} after {} attempts: {}", path, maxRetries, e.getMessage());
                            allDeleted = false;
                        } else {
                            // Wait before retry
                            Thread.sleep(retryDelayMs);
                        }
                    }
                }

                if (!deleted) {
                    LOGGER.warn("Could not delete file after {} attempts: {}", maxRetries, path);
                    allDeleted = false;
                }
            }
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Error during directory deletion: {}", e.getMessage());
            Thread.currentThread().interrupt(); // Restore interrupted status
            return false;
        }

        return allDeleted;
    }
    public File ensureTempDirectoryExists(Path path) {
        try {
            Files.createDirectories(path);
            return path.toFile();
        } catch (IOException e) {
            throw new RuntimeException("Impossibile creare la directory temporanea: " + path, e);
        }
    }

    protected void checkoutRelease(RevCommit commit, Path commitTempDir) {
        try {
            // First try: clean the working directory before checkout
            try {
                // Clean the working directory to remove any untracked files
                git.clean().setCleanDirectories(true).setForce(true).call();

                // Reset any changes to tracked files
                git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call();

                // Attempt checkout
                git.checkout()
                   .setName(commit.getName())
                   .call();
            } catch (GitAPIException firstAttemptException) {
                LOGGER.warn("First checkout attempt failed for commit {}: {}", 
                           commit.getName(), firstAttemptException.getMessage());

                // Second try: more aggressive approach with stash
                try {
                    // Reset the repository to a clean state
                    git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call();

                    // Try to stash any changes
                    try {
                        git.stashCreate().call();
                    } catch (Exception stashException) {
                        LOGGER.warn("Stash failed, continuing with checkout: {}", stashException.getMessage());
                    }

                    // Try checkout again
                    git.checkout()
                       .setName(commit.getName())
                       .call();
                } catch (GitAPIException secondAttemptException) {
                    LOGGER.warn("Second checkout attempt failed for commit {}: {}", 
                               commit.getName(), secondAttemptException.getMessage());

                    // Third try: handle the specific problematic file
                    try {
                        // Reset again
                        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call();

                        // Delete the problematic file if it exists
                        java.io.File problematicFile = new java.io.File(repository.getWorkTree(), 
                                                                      "openjpa-project/src/doc/manual/ref_guide_runtime.xml");
                        if (problematicFile.exists()) {
                            LOGGER.info("Deleting problematic file: {}", problematicFile.getPath());
                            problematicFile.delete();
                        }

                        // Try checkout one more time
                        git.checkout()
                           .setName(commit.getName())
                           .call();
                    } catch (GitAPIException thirdAttemptException) {
                        // If all attempts fail, log the error and throw the exception
                        LOGGER.error("Failed to checkout commit {} after multiple attempts: {}", 
                                    commit.getName(), thirdAttemptException.getMessage());
                        throw thirdAttemptException;
                    }
                }
            }

            ensureTempDirectoryExists(commitTempDir);
            exportCodeToDirectory(commit, commitTempDir);
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    private void exportCodeToDirectory(RevCommit commit, Path targetDir) {
        try {
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

            // If we still need more files, process from the current commit
            if (processedFilesCount < maxFilesToProcess) {
                RevWalk revWalk = new RevWalk(repository);
                RevCommit parent = commit.getParentCount() > 0 ? revWalk.parseCommit(commit.getParent(0).getId()) : null;

                DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
                df.setRepository(repository);
                df.setDiffComparator(RawTextComparator.DEFAULT);
                df.setDetectRenames(true);

                List<DiffEntry> diffs = df.scan(parent == null ? null : parent.getTree(), commit.getTree());

                // Collect all Java files from the commit
                List<DiffEntry> javaFiles = new ArrayList<>();
                for (DiffEntry entry : diffs) {
                    if (entry.getChangeType() != DiffEntry.ChangeType.DELETE) {
                        String path = entry.getNewPath();
                        if (path.endsWith(".java") && !isTestFile(path)) {
                            javaFiles.add(entry);
                        }
                    }
                }

                LOGGER.info("Found {} Java files in commit", javaFiles.size());

                // Process files up to the maximum limit
                for (int i = 0; i < javaFiles.size(); i++) {
                    DiffEntry entry = javaFiles.get(i);
                    String path = entry.getNewPath();

                    try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, commit.getTree())) {
                        if (treeWalk != null) {
                            byte[] content = repository.open(treeWalk.getObjectId(0)).getBytes();

                            // If we've reached the maximum, cache the remaining files
                            if (processedFilesCount >= maxFilesToProcess) {
                                cachedFiles.put(path, content);
                            } else {
                                // Otherwise, write the file to the target directory
                                Path targetFilePath = targetDir.resolve(path);
                                Files.createDirectories(targetFilePath.getParent());
                                Files.write(targetFilePath, content);
                                processedFilesCount++;
                            }
                        }
                    }
                }
            }

            // Count the actual number of files exported
            long count = 0;
            if (Files.exists(targetDir)) {
                count = Files.walk(targetDir)
                        .filter(p -> p.toString().endsWith(".java"))
                        .count();
            }

            LOGGER.info("Classi da processare: {}, Rimaste in cache: {}", count, cachedFiles.size());

        } catch (Exception e) {
            LOGGER.error("Errore durante l'esportazione dei file del commit: {} ", commit.getName(), e);
        }
    }
    private boolean isTestFile(String path) {
        String lowerPath = path.toLowerCase();
        return lowerPath.contains("/test/") || lowerPath.contains("test") || lowerPath.contains("mock");
    }

}
