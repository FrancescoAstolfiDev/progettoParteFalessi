package project.controllers;

import org.json.JSONException;
import org.json.JSONObject;
import project.models.MethodInstance;
import project.utils.ConstantsWindowsFormat;

import java.io.IOException;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;
import com.google.gson.Gson;
 /*
 * TODO :
 *  fare in modo che la cache contenga al più 1000
 *  commit oppure che la cache non può superare la taglia fissa di 50 mb
 *
 *
 *
 *
 * */
public class Caching {
    /**
     * Saves the commit cache to disk
     */
    private static final Path cacheDirPath = ConstantsWindowsFormat.CACHE_PATH;

    /**
     * Gets the cache file path for a specific project and index
     *
     * @param projectName The name of the project
     * @param index The index of the cache file (1, 2, 3, etc.), or 0 for no index
     * @return The path to the cache file for the project
     */
    private static Path getCacheFilePath(String projectName, int index) {
        if (index <= 0) {
            return cacheDirPath.resolve(projectName.toLowerCase() + "_commit_cache.json");
        } else {
            return cacheDirPath.resolve(projectName.toLowerCase() + "_commit_cache" + index + ".json");
        }
    }

    /**
     * Gets the cache file path for a specific project (backward compatibility)
     *
     * @param projectName The name of the project
     * @return The path to the cache file for the project
     */
    private static Path getCacheFilePath(String projectName) {
        return getCacheFilePath(projectName, 0);
    }

    /**
     * Gets all cache file paths for a specific project
     *
     * @param projectName The name of the project
     * @return Array of paths to all cache files for the project
     */
    private static Path[] getAllCacheFilePaths(String projectName) {
        try {
            // Get all files in the cache directory
            java.io.File cacheDir = cacheDirPath.toFile();
            if (!cacheDir.exists() || !cacheDir.isDirectory()) {
                return new Path[0];
            }

            // Filter files that match the project name pattern
            String baseFileName = projectName.toLowerCase() + "_commit_cache";
            java.io.File[] cacheFiles = cacheDir.listFiles((dir, name) -> 
                name.startsWith(baseFileName) && name.endsWith(".json"));

            if (cacheFiles == null || cacheFiles.length == 0) {
                return new Path[0];
            }

            // Convert to Path array
            Path[] cachePaths = new Path[cacheFiles.length];
            for (int i = 0; i < cacheFiles.length; i++) {
                cachePaths[i] = cacheFiles[i].toPath();
            }

            return cachePaths;
        } catch (Exception e) {
            System.err.println("Error getting cache files for project " + projectName + ": " + e.getMessage());
            e.printStackTrace();
            return new Path[0];
        }
    }

    /**
     * Saves the commit cache to disk with optimized performance and file size management
     *
     * @param resultCommitsMethods Map of commit hashes to method instances
     * @param projectName The name of the project
     */
    public static void saveCommitCache(Map<String, Map<String, MethodInstance>> resultCommitsMethods, String projectName) {
        long startTime = System.currentTimeMillis();

        try {
            // Import the MAX_CACHE_FILE_SIZE constant
            long maxCacheFileSize = project.utils.ConstantSize.MAX_CACHE_FILE_SIZE;

            // Get all existing cache files for this project
            Path[] existingCachePaths = getAllCacheFilePaths(projectName);

            // Map to store commit data by cache file index
            Map<Integer, JSONObject> cacheJsonByIndex = new HashMap<>();
            Map<Integer, Integer> methodCountByIndex = new HashMap<>();

            // Load existing cache files
            for (Path cachePath : existingCachePaths) {
                String fileName = cachePath.getFileName().toString();
                int cacheIndex = 0;

                // Extract index from filename (if any)
                if (fileName.matches(".*_commit_cache\\d+\\.json")) {
                    String indexStr = fileName.replaceAll(".*_commit_cache(\\d+)\\.json", "$1");
                    try {
                        cacheIndex = Integer.parseInt(indexStr);
                    } catch (NumberFormatException e) {
                        System.err.println("Error parsing cache file index: " + fileName);
                        continue;
                    }
                }

                // Load the cache file
                try (java.io.BufferedInputStream bis = new java.io.BufferedInputStream(
                        Files.newInputStream(cachePath), 8192)) {

                    org.json.JSONTokener tokener = new org.json.JSONTokener(bis);

                    // Check if it's a JSON object
                    if (tokener.nextClean() != '{') {
                        throw new JSONException("Expected a JSON object");
                    }

                    // Reset the tokener
                    tokener.back();

                    // Parse the JSON object
                    JSONObject cacheJson = new JSONObject(tokener);
                    cacheJsonByIndex.put(cacheIndex, cacheJson);

                    // Count methods
                    int methodCount = 0;
                    for (String commitHash : cacheJson.keySet()) {
                        JSONObject commitJson = cacheJson.getJSONObject(commitHash);
                        methodCount += commitJson.length();
                    }
                    methodCountByIndex.put(cacheIndex, methodCount);

                    System.out.println("Loaded existing cache for project " + projectName + 
                            (cacheIndex > 0 ? " (part " + cacheIndex + ")" : "") + 
                            " with " + cacheJson.length() + " commits");
                }
            }

            // If no cache files exist, create the first one
            if (cacheJsonByIndex.isEmpty()) {
                cacheJsonByIndex.put(0, new JSONObject());
                methodCountByIndex.put(0, 0);
            }

            // For each commit in the input data
            int commitCount = 0;
            int totalMethods = 0;

            for (Map.Entry<String, Map<String, MethodInstance>> commitEntry : resultCommitsMethods.entrySet()) {
                String commitHash = commitEntry.getKey();
                Map<String, MethodInstance> methodMap = commitEntry.getValue();

                // Check if this commit already exists in any cache file
                boolean commitFound = false;
                int targetCacheIndex = 0;

                for (Map.Entry<Integer, JSONObject> cacheEntry : cacheJsonByIndex.entrySet()) {
                    int cacheIndex = cacheEntry.getKey();
                    JSONObject cacheJson = cacheEntry.getValue();

                    if (cacheJson.has(commitHash)) {
                        // Commit found in this cache file
                        commitFound = true;
                        targetCacheIndex = cacheIndex;
                        break;
                    }
                }

                // If commit not found, determine which cache file to use
                if (!commitFound) {
                    // Find the cache file with the smallest estimated size
                    long smallestSize = Long.MAX_VALUE;

                    for (Map.Entry<Integer, JSONObject> cacheEntry : cacheJsonByIndex.entrySet()) {
                        int cacheIndex = cacheEntry.getKey();
                        JSONObject cacheJson = cacheEntry.getValue();

                        // Estimate file size based on JSON string length
                        long estimatedSize = cacheJson.toString().length();

                        if (estimatedSize < smallestSize) {
                            smallestSize = estimatedSize;
                            targetCacheIndex = cacheIndex;
                        }
                    }

                    // If smallest cache file is too large, create a new one
                    if (smallestSize >= maxCacheFileSize * 0.9) { // 90% of max size to leave some room
                        // Find the next available index
                        int nextIndex = 1;
                        while (cacheJsonByIndex.containsKey(nextIndex)) {
                            nextIndex++;
                        }

                        // Create a new cache file
                        cacheJsonByIndex.put(nextIndex, new JSONObject());
                        methodCountByIndex.put(nextIndex, 0);
                        targetCacheIndex = nextIndex;

                        System.out.println("Creating new cache file for project " + projectName + 
                                " (part " + nextIndex + ") as existing files are near size limit");
                    }
                }

                // Get the target cache JSON object
                JSONObject cacheJson = cacheJsonByIndex.get(targetCacheIndex);

                // Create or get the JSON object for this commit
                JSONObject commitJson;
                if (cacheJson.has(commitHash)) {
                    commitJson = cacheJson.getJSONObject(commitHash);
                } else {
                    commitJson = new JSONObject();
                }

                // For each method in the commit
                int methodsAdded = 0;
                for (Map.Entry<String, MethodInstance> methodEntry : methodMap.entrySet()) {
                    String methodKey = methodEntry.getKey();
                    MethodInstance method = methodEntry.getValue();

                    // Skip if this method is already in the cache for this commit
                    if (commitJson.has(methodKey)) {
                        continue;
                    }

                    // Create a JSON object for this method
                    JSONObject methodJson = new JSONObject();
                    methodJson.put("filePath", method.getFilePath());
                    methodJson.put("methodName", method.getMethodName());
                    methodJson.put("className", method.getClassName());
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
                    methodJson.put("buggy", method.isBuggy());

                    // Add the method to the commit JSON
                    commitJson.put(methodKey, methodJson);
                    methodsAdded++;
                    totalMethods++;

                    // Update method count for this cache file
                    methodCountByIndex.put(targetCacheIndex, methodCountByIndex.get(targetCacheIndex) + 1);
                }

                // Add the commit to the cache JSON
                cacheJson.put(commitHash, commitJson);
                commitCount++;

                // Log progress periodically
                if (commitCount % 100 == 0) {
                    System.out.println("Processed " + commitCount + " commits for saving...");
                }
            }

            // Write each cache file to disk
            for (Map.Entry<Integer, JSONObject> cacheEntry : cacheJsonByIndex.entrySet()) {
                int cacheIndex = cacheEntry.getKey();
                JSONObject cacheJson = cacheEntry.getValue();

                // Get the path for this cache file
                Path cacheFilePath = getCacheFilePath(projectName, cacheIndex);

                // Write the cache to disk using a BufferedWriter for better performance
                try (java.io.BufferedWriter writer = Files.newBufferedWriter(cacheFilePath)) {
                    // Use a more compact JSON representation to save space
                    writer.write(cacheJson.toString());
                }

                System.out.println("Commit cache for project " + projectName + 
                        (cacheIndex > 0 ? " (part " + cacheIndex + ")" : "") + 
                        " saved to " + cacheFilePath +
                        " with " + cacheJson.length() + " commits and " +
                        methodCountByIndex.get(cacheIndex) + " methods");
            }

            long endTime = System.currentTimeMillis();
            System.out.println("All commit caches for project " + projectName + " saved in " +
                    (endTime - startTime) + "ms with a total of " + totalMethods + " methods");

        } catch (IOException | JSONException e) {
            System.err.println("Error saving commit cache for project " + projectName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Backward compatibility method for saving commit cache without project name
     *
     * @param resultCommitsMethods Map of commit hashes to method instances
     */
    public static void saveCommitCache(Map<String, Map<String, MethodInstance>> resultCommitsMethods) {
        // Use a default project name
        saveCommitCache(resultCommitsMethods, "default");
    }

    /**
     * Loads the commit cache from disk with optimized performance
     *
     * @param resultCommitsMethods Map to store the loaded commits
     * @param projectName The name of the project
     */
    public static void loadCommitCache(Map<String, Map<String, MethodInstance>> resultCommitsMethods, String projectName) {
        // Load all commits (no filtering)
        loadCommitCache(resultCommitsMethods, null, projectName);
    }

    /**
     * Backward compatibility method for loading commit cache without project name
     *
     * @param resultCommitsMethods Map to store the loaded commits
     */
    public static void loadCommitCache(Map<String, Map<String, MethodInstance>> resultCommitsMethods) {
        // Use a default project name
        loadCommitCache(resultCommitsMethods, "default");
    }

    /**
     * Helper class to store commit data with timestamp
     */
    private static class CommitData {
        Map<String, MethodInstance> methods;
        long timestamp;

        CommitData(Map<String, MethodInstance> methods, long timestamp) {
            this.methods = methods;
            this.timestamp = timestamp;
        }
    }

    /**
     * Checks and resizes cache files if they exceed the maximum size limit
     *
     * @param projectName The name of the project
     * @return Array of paths to all cache files for the project after resizing
     */
    private static Path[] checkAndResizeCacheFiles(String projectName) {
        Path[] cachePaths = getAllCacheFilePaths(projectName);
        if (cachePaths.length == 0) return cachePaths;

        long maxCacheFileSize = project.utils.ConstantSize.MAX_CACHE_FILE_SIZE;
        int CHUNK_SIZE = 50; // Numero di commit per chunk

        try {
            // Map per tenere traccia dei commit già processati
            Set<String> processedCommits = new HashSet<>();
            Map<String, CommitData> commitsByHash = new HashMap<>();

            // 1. Prima fase: carica e deduplicazione
            for (Path cachePath : cachePaths) {
                if (Files.size(cachePath) > maxCacheFileSize) {
                    Map<String, Map<String, MethodInstance>> fileCommits = loadSingleCacheFile(cachePath);

                    // Processa ogni commit nel file
                    for (Map.Entry<String, Map<String, MethodInstance>> entry : fileCommits.entrySet()) {
                        String commitHash = entry.getKey();

                        // Se non è un duplicato o è più recente
                        if (!processedCommits.contains(commitHash) || 
                            (commitsByHash.containsKey(commitHash) && 
                             isMoreRecent(entry.getValue(), commitsByHash.get(commitHash).methods))) {

                            commitsByHash.put(commitHash, new CommitData(
                                entry.getValue(), 
                                Files.getLastModifiedTime(cachePath).toMillis()
                            ));
                            processedCommits.add(commitHash);
                        }
                    }

                    // Elimina il file originale
                    Files.delete(cachePath);
                }
            }

            // 2. Seconda fase: redistribuzione a chunk
            List<Map.Entry<String, CommitData>> sortedCommits = new ArrayList<>(
                commitsByHash.entrySet().stream()
                    .map(e -> Map.entry(e.getKey(), e.getValue()))
                    .sorted((a, b) -> Long.compare(b.getValue().timestamp, a.getValue().timestamp))
                    .collect(Collectors.toList())
            );

            // Processa per chunk
            for (int i = 0; i < sortedCommits.size(); i += CHUNK_SIZE) {
                int end = Math.min(i + CHUNK_SIZE, sortedCommits.size());
                Map<String, Map<String, MethodInstance>> chunkData = new HashMap<>();

                // Prepara il chunk
                for (int j = i; j < end; j++) {
                    Map.Entry<String, CommitData> entry = sortedCommits.get(j);
                    chunkData.put(entry.getKey(), entry.getValue().methods);
                }

                // Trova il file cache meno pieno
                Path targetCache = findLeastFullCache(getAllCacheFilePaths(projectName));

                // Se tutti i file sono troppo grandi o non esistono, crea un nuovo file
                if (targetCache == null || Files.size(targetCache) > maxCacheFileSize * 0.9) {
                    targetCache = createNewCacheFile(projectName);
                }

                // Salva il chunk
                appendToCache(targetCache, chunkData);
            }

            return getAllCacheFilePaths(projectName);

        } catch (Exception e) {
            System.err.println("Error during cache resize: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to resize cache files", e);
        }
    }

    /**
     * Determines if a set of methods is more recent than another
     *
     * @param newMethods The new methods
     * @param existingMethods The existing methods
     * @return True if the new methods are more recent
     */
    private static boolean isMoreRecent(Map<String, MethodInstance> newMethods, 
                                      Map<String, MethodInstance> existingMethods) {
        // Confronta l'età o altre metriche per determinare quale versione è più recente
        // Nota: un'età più bassa indica un metodo più recente
        return newMethods.values().stream()
                        .mapToInt(m -> m.getAge())
                        .min()
                        .orElse(Integer.MAX_VALUE) < 
               existingMethods.values().stream()
                             .mapToInt(m -> m.getAge())
                             .min()
                             .orElse(Integer.MAX_VALUE);
    }

    /**
     * Finds the least full cache file
     *
     * @param cachePaths Array of cache file paths
     * @return The path to the least full cache file, or null if none exist
     * @throws IOException If there's an error reading the file sizes
     */
    private static Path findLeastFullCache(Path[] cachePaths) throws IOException {
        if (cachePaths.length == 0) return null;

        return Arrays.stream(cachePaths)
                    .min(Comparator.comparingLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return Long.MAX_VALUE;
                        }
                    }))
                    .orElse(null);
    }

    /**
     * Creates a new cache file with a unique name
     *
     * @param projectName The name of the project
     * @return The path to the new cache file
     * @throws IOException If there's an error creating the file
     */
    private static Path createNewCacheFile(String projectName) throws IOException {
        String baseFileName = projectName.toLowerCase() + "_commit_cache";
        int fileCounter = 0;
        Path newPath;

        do {
            newPath = cacheDirPath.resolve(
                baseFileName + "_" + String.format("%03d", fileCounter++) + ".json"
            );
        } while (Files.exists(newPath));

        Files.createFile(newPath);
        return newPath;
    }

    /**
     * Appends commits to a cache file
     *
     * @param cachePath The path to the cache file
     * @param commits The commits to append
     * @throws IOException If there's an error writing to the file
     */
    private static void appendToCache(Path cachePath, 
                                    Map<String, Map<String, MethodInstance>> commits) 
            throws IOException {
        // Implementazione del merge con il contenuto esistente
        Map<String, Map<String, MethodInstance>> existing = 
            Files.exists(cachePath) ? loadSingleCacheFile(cachePath) : new HashMap<>();

        existing.putAll(commits);

        // Salva il file aggiornato
        try (BufferedWriter writer = Files.newBufferedWriter(cachePath)) {
            new Gson().toJson(existing, writer);
        }
    }

    /**
     * Loads specific commits from the cache
     *
     * @param resultCommitsMethods Map to store the loaded commits
     * @param commitHashes Set of commit hashes to load, or null to load all commits
     * @param projectName The name of the project
     */
    public static void loadCommitCache(Map<String, Map<String, MethodInstance>> resultCommitsMethods, Set<String> commitHashes, String projectName) {
        // Check and resize cache files if needed
        Path[] cachePaths = checkAndResizeCacheFiles(projectName);

        if (cachePaths.length == 0) {
            System.out.println("No commit cache found for project " + projectName);
            return;
        }

        long startTime = System.currentTimeMillis();
        int totalCommitCount = 0;
        int totalMethodCount = 0;
        int totalSkippedCommits = 0;

        // Process each cache file
        for (Path cacheFilePath : cachePaths) {
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

                    int commitCount = 0;
                    int methodCount = 0;
                    int skippedCommits = 0;

                    // For each commit in the cache
                    for (String commitHash : cache.keySet()) {
                        // Skip commits not in the requested set (if filtering is enabled)
                        if (commitHashes != null && !commitHashes.contains(commitHash)) {
                            skippedCommits++;
                            totalSkippedCommits++;
                            continue;
                        }

                        // Skip if this commit is already loaded (from another cache file)
                        if (resultCommitsMethods.containsKey(commitHash)) {
                            continue;
                        }

                        JSONObject commitJson = cache.getJSONObject(commitHash);
                        Map<String, MethodInstance> methodMap = new HashMap<>();

                        // For each method in the commit
                        for (String methodKey : commitJson.keySet()) {
                            JSONObject methodJson = commitJson.getJSONObject(methodKey);

                            // Create a method instance with optimized property setting
                            MethodInstance method = new MethodInstance();
                            method.setFilePath(methodJson.optString("filePath", ""));
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
                            method.setBuggy(methodJson.optBoolean("buggy", false));

                            // Add the method to the map
                            methodMap.put(methodKey, method);
                            methodCount++;
                            totalMethodCount++;
                        }

                        // Add the commit to the cache
                        resultCommitsMethods.put(commitHash, methodMap);
                        commitCount++;
                        totalCommitCount++;

                        // Log progress periodically to avoid console spam
                        if (totalCommitCount % 100 == 0) {
                            System.out.println("Loaded " + totalCommitCount + " commits so far...");
                        }
                    }

                    // Get the cache file index (if any)
                    String fileName = cacheFilePath.getFileName().toString();
                    String cacheIndexStr = "";
                    if (fileName.matches(".*_commit_cache\\d+\\.json")) {
                        cacheIndexStr = " (part " + fileName.replaceAll(".*_commit_cache(\\d+)\\.json", "$1") + ")";
                    }

                    System.out.println("Loaded commit cache for project " + projectName + cacheIndexStr +
                            " from " + cacheFilePath +
                            " with " + commitCount + " commits" +
                            " and " + methodCount + " methods");
                }
            } catch (IOException | JSONException e) {
                System.err.println("Error loading commit cache from " + cacheFilePath + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        long endTime = System.currentTimeMillis();
        String filterMsg = commitHashes != null ?
                " (filtered " + totalSkippedCommits + " commits)" : "";

        System.out.println("Loaded all commit caches for project " + projectName +
                " with a total of " + totalCommitCount + " commits" + filterMsg +
                " and " + totalMethodCount + " methods in " +
                (endTime - startTime) + "ms");
    }

    /**
     * Backward compatibility method for loading specific commits from the cache without project name
     *
     * @param resultCommitsMethods Map to store the loaded commits
     * @param commitHashes Set of commit hashes to load, or null to load all commits
     */
    public static void loadCommitCache(Map<String, Map<String, MethodInstance>> resultCommitsMethods, Set<String> commitHashes) {
        // Use a default project name
        loadCommitCache(resultCommitsMethods, commitHashes, "default");
    }

    /**
     * Gets a set of commit hashes available in the cache without loading their data
     *
     * @param projectName The name of the project
     * @return Set of commit hashes available in the cache, or empty set if cache doesn't exist
     */
    public static Set<String> getAvailableCommits(String projectName) {
        // Get all cache files for this project
        Path[] cachePaths = getAllCacheFilePaths(projectName);
        Set<String> availableCommits = new HashSet<>();

        if (cachePaths.length == 0) {
            System.out.println("No commit cache found for project " + projectName);
            return availableCommits;
        }

        long startTime = System.currentTimeMillis();
        int totalCacheFiles = 0;

        // Process each cache file
        for (Path cacheFilePath : cachePaths) {
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
                    int commitsBefore = availableCommits.size();
                    for (String commitHash : cache.keySet()) {
                        availableCommits.add(commitHash);
                    }

                    // Get the cache file index (if any)
                    String fileName = cacheFilePath.getFileName().toString();
                    String cacheIndexStr = "";
                    if (fileName.matches(".*_commit_cache\\d+\\.json")) {
                        cacheIndexStr = " (part " + fileName.replaceAll(".*_commit_cache(\\d+)\\.json", "$1") + ")";
                    }

                    System.out.println("Found " + (availableCommits.size() - commitsBefore) + 
                            " unique commits in cache file " + cacheFilePath + cacheIndexStr);

                    totalCacheFiles++;
                }
            } catch (IOException | JSONException e) {
                System.err.println("Error checking available commits in cache file " + cacheFilePath + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        long endTime = System.currentTimeMillis();
        System.out.println("Found a total of " + availableCommits.size() + " unique commits across " + 
                totalCacheFiles + " cache files for project " + projectName + " in " +
                (endTime - startTime) + "ms");

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
     * Loads a single cache file and returns its contents
     *
     * @param cachePath Path to the cache file
     * @return Map of commit hashes to method instances
     * @throws IOException If there's an error reading the file
     * @throws JSONException If there's an error parsing the JSON
     */
    private static Map<String, Map<String, MethodInstance>> loadSingleCacheFile(Path cachePath) 
            throws IOException, JSONException {
        Map<String, Map<String, MethodInstance>> resultCommits = new HashMap<>();

        // Use a buffered input stream for more efficient reading
        try (java.io.BufferedInputStream bis = new java.io.BufferedInputStream(
                Files.newInputStream(cachePath), 8192)) {

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

            // For each commit in the cache
            for (String commitHash : cache.keySet()) {
                JSONObject commitJson = cache.getJSONObject(commitHash);
                Map<String, MethodInstance> methodMap = new HashMap<>();

                // For each method in the commit
                for (String methodKey : commitJson.keySet()) {
                    JSONObject methodJson = commitJson.getJSONObject(methodKey);

                    // Create a method instance with optimized property setting
                    MethodInstance method = new MethodInstance();
                    method.setFilePath(methodJson.optString("filePath", ""));
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
                    method.setBuggy(methodJson.optBoolean("buggy", false));

                    // Add the method to the map
                    methodMap.put(methodKey, method);
                }

                // Add the commit to the result
                resultCommits.put(commitHash, methodMap);
            }
        }

        return resultCommits;
    }

}
