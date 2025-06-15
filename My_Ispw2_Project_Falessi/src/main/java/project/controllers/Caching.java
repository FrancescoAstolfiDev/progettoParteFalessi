package project.controllers;

import org.json.JSONException;
import org.json.JSONObject;
import project.models.MethodInstance;
import project.utils.ConstantsWindowsFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public class Caching {
    /**
     * Saves the commit cache to disk
     */
    private static final Path cacheDirPath = ConstantsWindowsFormat.cachePath;

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
                    System.out.println("Loaded existing cache for project " + projectName + " with " + cacheJson.length() + " commits");
                }
            }

            // For each commit in the cache
            int commitCount = 0;
            for (Map.Entry<String, Map<String, MethodInstance>> commitEntry : resultCommitsMethods.entrySet()) {
                String commitHash = commitEntry.getKey();
                Map<String, MethodInstance> methodMap = commitEntry.getValue();

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
                }

                // Add the commit to the cache JSON
                cacheJson.put(commitHash, commitJson);
                commitCount++;

                // Log progress periodically
                if (commitCount % 100 == 0) {
                    System.out.println("Processed " + commitCount + " commits for saving...");
                }
            }

            // Write the cache to disk using a BufferedWriter for better performance
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(cacheFilePath)) {
                // Use a more compact JSON representation to save space
                writer.write(cacheJson.toString());
            }

            long endTime = System.currentTimeMillis();
            System.out.println("Commit cache for project " + projectName + " saved to " + cacheFilePath +
                    " with " + cacheJson.length() + " commits and " +
                    totalMethods + " methods in " +
                    (endTime - startTime) + "ms");

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
     * Loads specific commits from the cache
     *
     * @param resultCommitsMethods Map to store the loaded commits
     * @param commitHashes Set of commit hashes to load, or null to load all commits
     * @param projectName The name of the project
     */
    public static void loadCommitCache(Map<String, Map<String, MethodInstance>> resultCommitsMethods, Set<String> commitHashes, String projectName) {
        Path cacheFilePath = getCacheFilePath(projectName);

        if (!Files.exists(cacheFilePath)) {
            System.out.println("No commit cache found at " + cacheFilePath);
            return;
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

                int commitCount = 0;
                int methodCount = 0;
                int skippedCommits = 0;

                // For each commit in the cache
                for (String commitHash : cache.keySet()) {
                    // Skip commits not in the requested set (if filtering is enabled)
                    if (commitHashes != null && !commitHashes.contains(commitHash)) {
                        skippedCommits++;
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
                    }

                    // Add the commit to the cache
                    resultCommitsMethods.put(commitHash, methodMap);
                    commitCount++;

                    // Log progress periodically to avoid console spam
                    if (commitCount % 100 == 0) {
                        System.out.println("Loaded " + commitCount + " commits so far...");
                    }
                }

                long endTime = System.currentTimeMillis();
                String filterMsg = commitHashes != null ?
                        " (filtered " + skippedCommits + " commits)" : "";

                System.out.println("Loaded commit cache for project " + projectName + " from " + cacheFilePath +
                        " with " + commitCount + " commits" + filterMsg + " and " +
                        methodCount + " methods in " +
                        (endTime - startTime) + "ms");
            }
        } catch (IOException | JSONException e) {
            System.err.println("Error loading commit cache for project " + projectName + ": " + e.getMessage());
            e.printStackTrace();
        }
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
        Path cacheFilePath = getCacheFilePath(projectName);
        Set<String> availableCommits = new HashSet<>();

        if (!Files.exists(cacheFilePath)) {
            System.out.println("No commit cache found at " + cacheFilePath);
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
                System.out.println("Found " + availableCommits.size() + " commits in cache for project " + projectName + " in " +
                        (endTime - startTime) + "ms");
            }
        } catch (IOException | JSONException e) {
            System.err.println("Error checking available commits in cache for project " + projectName + ": " + e.getMessage());
            e.printStackTrace();
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
}