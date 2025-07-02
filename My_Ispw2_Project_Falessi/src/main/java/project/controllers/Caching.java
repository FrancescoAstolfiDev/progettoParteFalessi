package project.controllers;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import project.models.MethodInstance;
import project.statefull.ConstantsWindowsFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Caching {
    Caching(){}
    /**
     * Saves the commit cache to disk
     */
    private static final Path cacheDirPath = ConstantsWindowsFormat.CACHE_PATH;
    private static final String NO_COMMIT_CACHE_FOUND = "No commit cache found";
    private static final Logger LOGGER = LoggerFactory.getLogger(Caching.class);

    /**
     * Gets the cache file path for a specific project
     *
     * @param projectName The name of the project
     * @return The path to the cache file for the project
     */
    private static Path getCacheFilePath(String projectName) {
        return cacheDirPath.resolve(projectName.toLowerCase() + "_commit_cache.json");
    }

    /**
     * Saves the commit cache to disk with optimized performance
     *
     * @param resultCommitsMethods Map of commit hashes to method instances
     * @param projectName The name of the project
     */
    public static void saveCommitCache(Map<String, Map<String, MethodInstance>> resultCommitsMethods, String projectName) {
        long startTime = System.currentTimeMillis();

        try {
            // Create a JSON object to hold the cache
            JSONObject cacheJson = new JSONObject();
            int totalMethods = 0;

            // Load existing cache if it exists
            Path cacheFilePath = getCacheFilePath(projectName);
            if (Files.exists(cacheFilePath)) {
                try (java.io.BufferedInputStream bis = new java.io.BufferedInputStream(
                        Files.newInputStream(cacheFilePath), 8192)) {

                    org.json.JSONTokener tokener = new org.json.JSONTokener(bis);

                    // Check if it's a JSON object
                    if (tokener.nextClean() != '{') {
                        throw new JSONException("Expected a JSON object");
                    }

                    // Reset the tokener
                    tokener.back();

                    // Parse the JSON object
                    cacheJson = new JSONObject(tokener);
                    LOGGER.info("Loaded existing cache for project {} with {} commits" , projectName , cacheJson.length());
                }
            }

            // For each commit in the cache
            int commitCount = 0;
            for (Map.Entry<String, Map<String, MethodInstance>> commitEntry : resultCommitsMethods.entrySet()) {
                String commitHash = commitEntry.getKey();
                Map<String, MethodInstance> methodMap = commitEntry.getValue();

                // Create or get the JSON object for this commit
                JSONObject commitJson;
                commitJson =cacheJson.has(commitHash)? cacheJson.getJSONObject(commitHash): new JSONObject();
                // For each method in the commit
                for (Map.Entry<String, MethodInstance> methodEntry : methodMap.entrySet()) {
                    String methodKey = methodEntry.getKey();
                    MethodInstance method = methodEntry.getValue();

                    // Create a JSON object for this method or update existing one
                    JSONObject methodJson;
                    if (commitJson.has(methodKey)) {
                        methodJson = commitJson.getJSONObject(methodKey);
                    } else {
                        methodJson = new JSONObject();
                    }
                    methodJson.put("filePath", method.getClassPath());
                    methodJson.put("methodName", method.getMethodName());
                    methodJson.put("className",method.getClassName());
                    methodJson.put("loc", method.getLoc());
                    methodJson.put("wmc", method.getWmc());
                    methodJson.put("qtyAssigment", method.getQtyAssigment());
                    methodJson.put("qtyMathOperations", method.getQtyMathOperations());
                    methodJson.put("qtyTryCatch", method.getQtyTryCatch());
                    methodJson.put("qtyReturn", method.getQtyReturn());
                    methodJson.put("fanin", method.getFanin());
                    methodJson.put("fanout", method.getFanout());
                    methodJson.put("age", method.getAge());
                    methodJson.put("nAuth", method.getnAuth());
                    methodJson.put("nr", method.getNr());
                    methodJson.put("nSmells", method.getnSmells());
                    // Removed buggyness value from cache as per requirements

                    // Add the method to the commit JSON
                    commitJson.put(methodKey, methodJson);
                    totalMethods++;
                }

                // Add the commit to the cache JSON
                cacheJson.put(commitHash, commitJson);
                commitCount++;

                // Log progress periodically
                if (commitCount % 100 == 0) {
                    LOGGER.info("Processed {} commits for saving " ,  commitCount );
                }
            }

            // Write the cache to disk using a BufferedWriter for better performance
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(cacheFilePath)) {
                // Use a more compact JSON representation to save space
                writer.write(cacheJson.toString());
            }

            long endTime = System.currentTimeMillis();
            LOGGER.info("Commit cache for project {} saved to {} with {} commits and {} methods in {} ms" , projectName , cacheFilePath ,
                    cacheJson.length(), totalMethods , (endTime - startTime) );

        } catch (IOException | JSONException e) {
            LOGGER.error("Error saving commit cache for project {} : {} error " ,  projectName , e.getMessage()  );

        }
    }



    /**
     * Loads specific commits from the cache
     *
     * @param resultCommitsMethods Map to store the loaded commits
     * @param commitHashes Set of commit hashes to load, or null to load all commits
     * @param projectName The name of the project
     */

    public static void loadCommitCache(Map<String, Map<String, MethodInstance>> resultCommitsMethods,
                                       Set<String> commitHashes,
                                       String projectName) {
        Path cacheFilePath = getCacheFilePath(projectName);

        if (!handleCacheFileExistence(cacheFilePath, projectName)) {
            return;
        }

        long startTime = System.currentTimeMillis();
        try {
            JSONObject cache = loadJsonCache(cacheFilePath);
            processCommits(cache, resultCommitsMethods, commitHashes, projectName, startTime);
        } catch (IOException | JSONException e) {
            handleCacheError(e, cacheFilePath, projectName);
        }
    }

    private static void handleCacheError(Exception e, Path cacheFilePath, String projectName) {
        LOGGER.error("Error loading commit cache for project {} : {} ", projectName, e.getMessage());

        if (e instanceof JSONException && e.getMessage().contains("Expected a JSON object")) {
            recreateInvalidCache(cacheFilePath, projectName);
        }
    }
    private static void recreateInvalidCache(Path cacheFilePath, String projectName) {
        try {
            Files.delete(cacheFilePath);
            LOGGER.info("Deleted invalid cache file for project {}: {}", projectName, cacheFilePath);
            createEmptyCacheFile(cacheFilePath, projectName);
        } catch (IOException ex) {
            LOGGER.error("Error recreating cache file for project {}: {}", projectName, ex.getMessage());
        }
    }



    private static boolean handleCacheFileExistence(Path cacheFilePath, String projectName) {
        if (Files.exists(cacheFilePath)) {
            return true;
        }

        LOGGER.error("{} {}", NO_COMMIT_CACHE_FOUND, cacheFilePath);
        createEmptyCacheFile(cacheFilePath, projectName);
        return false;
    }

    private static void createEmptyCacheFile(Path cacheFilePath, String projectName) {
        try {
            Files.createDirectories(cacheFilePath.getParent());
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(cacheFilePath)) {
                writer.write("{}");
            }
            LOGGER.info("Created new empty cache file for project {}: {}", projectName, cacheFilePath);
        } catch (IOException e) {
            LOGGER.error("Error creating new cache file for project {}: {}", projectName, e.getMessage());
        }
    }

    private static JSONObject loadJsonCache(Path cacheFilePath) throws IOException, JSONException {
        try (java.io.BufferedInputStream bis = new java.io.BufferedInputStream(
                Files.newInputStream(cacheFilePath), 8192)) {
            org.json.JSONTokener tokener = new org.json.JSONTokener(bis);
            validateJsonStructure(tokener);
            return new JSONObject(tokener);
        }
    }

    private static void validateJsonStructure(org.json.JSONTokener tokener) throws JSONException {
        if (tokener.nextClean() != '{') {
            throw new JSONException("Expected a JSON object");
        }
        tokener.back();
    }

    private static void processCommits(JSONObject cache,
                                       Map<String, Map<String, MethodInstance>> resultCommitsMethods,
                                       Set<String> commitHashes,
                                       String projectName,
                                       long startTime) {
        CommitProcessingStats stats = new CommitProcessingStats();

        for (String commitHash : cache.keySet()) {
            if (shouldSkipCommit(commitHash, commitHashes)) {
                stats.incrementSkippedCommits();
                continue;
            }

            processCommit(cache, commitHash, resultCommitsMethods, stats);
            logProgressIfNeeded(stats.getCommitCount());
        }

        logFinalStats(stats, projectName, startTime, commitHashes);
    }

    private static void logFinalStats(CommitProcessingStats stats, String projectName, long startTime, Set<String> commitHashes) {
        long endTime = System.currentTimeMillis();
        String filterMsg = createFilterMessage(stats, commitHashes);

        LOGGER.info("Loaded commit cache for project {} with {} commits{} and {} methods in {} ms",
                projectName,
                stats.getCommitCount(),
                filterMsg,
                stats.getMethodCount(),
                (endTime - startTime));
    }

    private static String createFilterMessage(CommitProcessingStats stats, Set<String> commitHashes) {
        if (commitHashes != null) {
            return String.format(" (filtered %d commits)", stats.getSkippedCommits());
        }
        return "";
    }


    private static void logProgressIfNeeded(int commitCount) {
        if (commitCount % 100 == 0) {
            LOGGER.info("Loaded {} commits so far...", commitCount);
        }
    }


    private static boolean shouldSkipCommit(String commitHash, Set<String> commitHashes) {
        return commitHashes != null && !commitHashes.contains(commitHash);
    }

    private static void processCommit(JSONObject cache,
                                      String commitHash,
                                      Map<String, Map<String, MethodInstance>> resultCommitsMethods,
                                      CommitProcessingStats stats) {
        JSONObject commitJson = cache.getJSONObject(commitHash);
        Map<String, MethodInstance> methodMap = new HashMap<>();

        for (String methodKey : commitJson.keySet()) {
            JSONObject methodJson = commitJson.getJSONObject(methodKey);
            methodMap.put(methodKey, createMethodInstance(methodJson));
            stats.incrementMethodCount();
        }

        resultCommitsMethods.put(commitHash, methodMap);
        stats.incrementCommitCount();
    }

    private static MethodInstance createMethodInstance(JSONObject methodJson) {
        MethodInstance method = new MethodInstance();
        method.setClassPath(methodJson.optString("filePath", ""));
        method.setMethodName(methodJson.optString("methodName", ""));
        method.setClassName(methodJson.optString("className", ""));
        method.setLoc(methodJson.optInt("loc", 0));
        method.setWmc(methodJson.optInt("wmc", 0));
        method.setQtyAssigment(methodJson.optInt("qtyAssigment", 0));
        method.setQtyMathOperations(methodJson.optInt("qtyMathOperations", 0));
        method.setQtyTryCatch(methodJson.optInt("qtyTryCatch", 0));
        method.setQtyReturn(methodJson.optInt("qtyReturn", 0));
        method.setFanin(methodJson.optInt("fanin", 0));
        method.setFanout(methodJson.optInt("fanout", 0));
        method.setAge(methodJson.optInt("age", 0));
        method.setnAuth(methodJson.optInt("nAuth", 0));
        method.setNr(methodJson.optInt("nr", 0));
        method.setnSmells(methodJson.optInt("nSmells", 0));
        method.setBuggy(false);
        return method;
    }

    private static class CommitProcessingStats {
        private int commitCount = 0;
        private int methodCount = 0;
        private int skippedCommits = 0;

        public void incrementCommitCount() { commitCount++; }
        public void incrementMethodCount() { methodCount++; }
        public void incrementSkippedCommits() { skippedCommits++; }
        public int getCommitCount() { return commitCount; }
        public int getMethodCount() { return methodCount; }
        public int getSkippedCommits() { return skippedCommits; }
    }



    /**
     * Gets a set of commit hashes available in the cache without loading their data
     *
     * @param projectName The name of the project
     * @return Set of commit hashes available in the cache, or empty set if cache doesn't exist
     */
    public static Set<String> getAvailableCommits(String projectName) {
        Path cacheFilePath = getCacheFilePath(projectName);
        Set<String> availableCommits = new HashSet<>();

        if (!Files.exists(cacheFilePath)) {
            LOGGER.error("{}  {} ", NO_COMMIT_CACHE_FOUND,  cacheFilePath);
            return availableCommits;
        }

        long startTime = System.currentTimeMillis();
        try {
            // Use a buffered input stream for more efficient reading
            try (java.io.BufferedInputStream bis = new java.io.BufferedInputStream(
                    Files.newInputStream(cacheFilePath), 8192)) {

                // Create a JSON parser that doesn't load the entire file into memory
                org.json.JSONTokener tokener = new org.json.JSONTokener(bis);

                // Check if it's a JSON object
                if (tokener.nextClean() != '{') {
                    throw new JSONException("Expected a JSON object");
                }

                // Reset the tokener
                tokener.back();

                // Parse the JSON object
                JSONObject cache = new JSONObject(tokener);

                // Add all commit hashes to the set
                for (String commitHash : cache.keySet()) {
                    availableCommits.add(commitHash);
                }

                long endTime = System.currentTimeMillis();
                LOGGER.info("Found {} commits in cache for the project {} in {} ms " , availableCommits.size() , projectName ,
                        (endTime - startTime));
            }
        } catch (IOException | JSONException e) {
            LOGGER.info("Error checking available commits in cache for project {}  :  {}" , projectName ,  e.getMessage());

        }

        return availableCommits;
    }

    /**
     * Backward compatibility method for getting available commits without project name
     *
     * @return Set of commit hashes available in the cache, or empty set if cache doesn't exist
     */
    public static Set<String> getAvailableCommits() {
        // Use a default project name
        return getAvailableCommits("default");
    }

    /**
     * Rimuove i primi N commit dalla cache di un progetto
     * @param projectName Il nome del progetto
     * @param numCommitsToRemove Numero di commit da rimuovere (ordinati per data)
     * @return Il numero di commit effettivamente rimossi
     */
    public static int removeOldestCommitsFromCache(String projectName, int numCommitsToRemove) {
        Path cacheFilePath = getCacheFilePath(projectName);

        if (!Files.exists(cacheFilePath)) {
            LOGGER.error("{}  {} ", NO_COMMIT_CACHE_FOUND,  cacheFilePath);
            return 0;
        }

        try {
            // Leggi la cache esistente
            JSONObject cacheJson;
            try (java.io.BufferedInputStream bis = new java.io.BufferedInputStream(
                    Files.newInputStream(cacheFilePath), 8192)) {
                org.json.JSONTokener tokener = new org.json.JSONTokener(bis);
                cacheJson = new JSONObject(tokener);
            }

            // Converti le chiavi in una lista ordinata
            List<String> commitHashes = new ArrayList<>(cacheJson.keySet());

            // Determina quanti commit rimuovere (non più di quanti ne esistono)
            int commitsToRemove = Math.min(numCommitsToRemove, commitHashes.size());

            if (commitsToRemove == 0) {
                LOGGER.info("No commits to remove from cache");
                return 0;
            }

            // Rimuovi i primi N commit
            int removedCount = 0;
            for (int i = 0; i < commitsToRemove; i++) {
                String commitHash = commitHashes.get(i);
                cacheJson.remove(commitHash);
                removedCount++;
            }

            // Scrivi la cache aggiornata
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(cacheFilePath)) {
                writer.write(cacheJson.toString());
            }

            LOGGER.info("Successfully removed {} commits from cache for project {} ", removedCount , projectName);
            LOGGER.info("Remaining commits in cache: {} ", cacheJson.length());

            return removedCount;

        } catch (IOException | JSONException e) {
            LOGGER.error("Error removing commits from cache for project {}  :  {} ", projectName, e.getMessage());
            return 0;
        }
    }

}
