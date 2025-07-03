package project.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import project.models.DataSetType;
import project.models.MethodInstance;
import project.models.Release;
import project.statefull.ConstantsWindowsFormat;
import project.utils.Projects;
import project.utils.WhatIf;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public class ClassWriter {
    ClassWriter(){

    }
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
            outPath=projectName.toUpperCase()+dataType+ actRelease.getId() ;
            LOGGER.info("Writing  results to {}" , outPath);
            writeResultsToFile(outPath, partialResults,dataType);
            Projects projects=  Projects.fromString(projectName);
            if(actRelease.getId()==projects.getNumStepDataset()+1){
                for(String matrix: Objects.requireNonNull(WhatIf.getListMatrix())){
                    writeResultsToFile(outPath+ matrix, partialResults,dataType);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error writing results: {}" , e.getMessage());
        }
    }

    static void writeResultsToFile(String path, Map<String, MethodInstance> results, DataSetType dataSetType) {
        Path outputFilePath;
        path=path+".csv";

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
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath.toFile(), false))
            ) {
                // Header
                writer.write(String.join(",",
                        "release", "method", "path","className",
                        "loc", "wmc", "assignmentsQty", "mathOperationsQty", "qtyTryCatch", "qtyReturn", "fanin", "fanout",
                        "age","nAuth", "nr", "nSmell","buggy"));
                writer.newLine();
                int writtenResults = 0;
                int valueSmell;
                for (MethodInstance result : results.values()) {
                    if(shouldSkipResult(result,path) ) {
                        continue;
                    }
                    valueSmell=result.getnSmells();
                    // For B matrix, set nSmells to 0 but keep the original instances
                    // For B_PLUS matrix, keep nSmells > 0
                    if (path.contains(WhatIf.B_MATRIX.getName()) && !path.contains(WhatIf.B_PLUS_MATRIX.getName())) {
                       valueSmell=0;
                    }

                    String csvRow = String.join(",",
                            escapeCsv(result.getReleaseName()),
                            escapeCsv(result.getMethodName()),
                            escapeCsv(String.valueOf(result.getClassPath())),
                            escapeCsv(String.valueOf(result.getClassName())),

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
                            String.valueOf(valueSmell),
                            String.valueOf(result.isBuggy())
                    );

                    writer.write(csvRow);
                    writer.newLine();
                    writtenResults++;
                }
                LOGGER.info("Successfully wrote {} results to {}\n\n", writtenResults, path);
            }
        } catch (IOException e) {
            LOGGER.error("Error writing partial results to file: {}" , e.getMessage());
        }
    }
    private static boolean shouldSkipResult(MethodInstance result, String path) {
        // Verifica condizioni base
        if (result.getAge() < 0 || result.getReleaseName() == null) {
            return true;
        }
        // Gestione matrice B - include instances with nSmells > 0, then set nSmells to 0
        if (path.contains(WhatIf.B_MATRIX.getName()) && !path.contains(WhatIf.B_PLUS_MATRIX.getName())) {
            return result.getnSmells() == 0;
        }
        // Gestione matrice B_PLUS - include all instances and keep nSmells as is
        if (path.contains(WhatIf.B_PLUS_MATRIX.getName())) {
            return result.getnSmells() == 0;
        }
        // Gestione matrice C - include only instances with nSmells == 0
        if (path.contains(WhatIf.C_MATRIX.getName())) {
            return result.getnSmells() > 0;
        }

        return false;
    }

}
