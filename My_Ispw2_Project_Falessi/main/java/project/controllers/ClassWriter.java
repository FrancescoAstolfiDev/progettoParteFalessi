package project.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import project.models.MethodInstance;
import project.models.Release;
import project.utils.ConstantsWindowsFormat;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class ClassWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClassWriter.class);

    // Funzione per escape CSV sicuro
    private static  String escapeCsv(String field) {
        if (field == null) return "";
        boolean hasSpecial = field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r") ;
        if (hasSpecial) {
            field = field.replace("\"", "\"\""); // raddoppia le virgolette
            return "\"" + field + "\"";
        }
        return field;
    }

    public static void writeResultsToFile(Release actRelease, String projectName, Map<String, MethodInstance> partialResults, boolean allResultsReceived) {
        if (actRelease == null) {
            LOGGER.error("Received partial results but currentProcessingRelease is null");
            return;
        }
        String outPath;
        try {
            // Use the same naming convention as the final results, but with a "Partial" prefix
            if(!allResultsReceived){
                outPath = projectName.toUpperCase() + "_Partial_Train_Method_Release_" +
                        actRelease.getName() + "_" +
                        System.currentTimeMillis() + ".csv";

                LOGGER.info("Writing partial results to {}" , outPath);

            }
            else{
                outPath = projectName.toUpperCase() + "_Train_Method_Release_" +actRelease.getName() + ".csv";
                LOGGER.info("Writing full results to " + outPath);
            }
            writePartialResultsToFile(outPath, partialResults);

        } catch (Exception e) {
            LOGGER.error("Error writing results: {}" , e.getMessage());
        }
    }

    private static void writePartialResultsToFile( String path, Map<String, MethodInstance> results) {
        Path outputFilePath;
        if(path.contains("Partial")){
            outputFilePath = ConstantsWindowsFormat.PARTIALS_CSV_PATH.resolve(path);
        }else{
            outputFilePath = ConstantsWindowsFormat.CSV_PATH.resolve(path);
        }
        try {
            if (Files.notExists(outputFilePath.getParent())) {
                Files.createDirectories(outputFilePath.getParent());
            }

            if (Files.notExists(outputFilePath)) {
                Files.createFile(outputFilePath);
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath.toFile()))) {
                // Header
                writer.write(String.join(",",
                        "release", "class", "method", "path",
                        "loc", "wmc", "assignmentsQty", "mathOperationsQty", "qtyTryCatch", "qtyReturn", "fanin", "fanout",
                        "age","nAuth", "nr", "nSmell","buggy"));
                writer.newLine();
                for (MethodInstance result : results.values()) {
                    if(result.getAge()<0){
                        continue;
                    }
                    String csvRow = String.join(",",
                            escapeCsv(result.getRelease().getName()),
                            escapeCsv(result.getClassName()),
                            escapeCsv(result.getMethodName()),
                            escapeCsv(String.valueOf(result.getFilePath())),

                            String.valueOf(result.getLoc()),
                            String.valueOf(result.getWmc()),
                            String.valueOf(result.getQtyAssigment()),
                            String.valueOf(result.getQtyMathOperations()),
                            String.valueOf(result.getQtyTryCatch()),
                            String.valueOf(result.getQtyReturn()),
                            String.valueOf(result.getFanin()),
                            String.valueOf(result.getFanout()),

                            String.valueOf(result.getAge()),
                            String.valueOf(result.getnAuth()),
                            String.valueOf(result.getNr()),
                            String.valueOf(result.getnSmells()),
                            String.valueOf(result.isBuggy())
                    );

                    writer.write(csvRow);
                    writer.newLine();
                }

                LOGGER.info("Successfully wrote {} results to {}", results.size() , path);
            }
        } catch (IOException e) {
            LOGGER.error("Error writing partial results to file: {}" , e.getMessage());
        }
    }
}
