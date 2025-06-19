package project.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import project.models.DataSetType;
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

    public static void writeResultsToFile(Release actRelease, String projectName, Map<String, MethodInstance> partialResults, DataSetType dataType) {
        if (actRelease == null) {
            LOGGER.error("Received partial results but currentProcessingRelease is null");
            return;
        }
        String outPath;
        try {
            outPath=projectName.toUpperCase()+dataType+ actRelease.getId() + ".csv";
            LOGGER.info("Writing  results to {}" , outPath);
            writeResultsToFile(outPath, partialResults,dataType);

        } catch (Exception e) {
            LOGGER.error("Error writing results: {}" , e.getMessage());
        }
    }

    private static void writeResultsToFile(String path, Map<String, MethodInstance> results, DataSetType dataSetType) {
        Path outputFilePath;

        if (dataSetType== DataSetType.PARTIAL){
            outputFilePath = ConstantsWindowsFormat.PARTIALS_CSV_PATH.resolve(path);
        } else if (dataSetType==DataSetType.TEST) {
            outputFilePath = ConstantsWindowsFormat.TEST_CSV_PATH.resolve(path);
        }else{
            outputFilePath = ConstantsWindowsFormat.CSV_PATH.resolve(path);
        }
        try {
            if (Files.notExists(outputFilePath.getParent())) {
                Files.createDirectories(outputFilePath.getParent());
            }

            if (Files.notExists(outputFilePath)) {
                Files.createFile(outputFilePath);
            }else {

            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath.toFile(), false))
            ) {
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
