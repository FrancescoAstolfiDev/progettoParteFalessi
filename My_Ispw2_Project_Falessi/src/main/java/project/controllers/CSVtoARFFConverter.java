package project.controllers;

import weka.core.Instances;
import weka.core.converters.ArffSaver;
import weka.core.converters.CSVLoader;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Remove;

import java.io.File;

import static java.lang.System.err;
import static java.lang.System.out;

public class CSVtoARFFConverter {
    private CSVtoARFFConverter() {
    }

    public static void executeConversion(String projectName, int numOFRelease) {
        String csvPath = project.utils.ConstantsWindowsFormat.CSV_PATH.toString();
        String testCsvPath = project.utils.ConstantsWindowsFormat.TEST_CSV_PATH.toString();
        String arffCsvPath=  project.utils.ConstantsWindowsFormat.ARFF_PATH.toString();

        for (int i = 2; i < numOFRelease; i++) {
            try {
                // Verifica esistenza directory
                createDirectoryIfNotExists(csvPath);
                createDirectoryIfNotExists(testCsvPath);

                String csvFilePathTrain = csvPath + "\\" + projectName.toUpperCase() + "Train" + i + ".csv";
                String csvFilePathTest = testCsvPath + "\\" + projectName.toUpperCase() + "Test" + (i+1) + ".csv";

                String arffFilePathTrain = arffCsvPath + "\\" + projectName + "_Train_R" + i + ".arff";
                String arffFilePathTest = arffCsvPath + "\\" + projectName + "_Test_R" + i + ".arff";

                // Verifica esistenza file
                if (!new File(csvFilePathTrain).exists() || !new File(csvFilePathTest).exists()) {
                    err.println("File CSV mancante per la Release " + i);
                    continue;
                }

                // Configurazione per il training set
                convertFile(csvFilePathTrain, arffFilePathTrain, projectName + "_Train_R" + i);

                // Configurazione per il test set
                convertFile(csvFilePathTest, arffFilePathTest, projectName + "_Test_R" + i);

                out.println("Conversione completata per Release " + i);

            } catch (Exception e) {
                err.println("Errore durante la conversione della Release " + i + ": " + e.getMessage());
            }
        }
    }

    private static void createDirectoryIfNotExists(String path) {
        File directory = new File(path);
        if (!directory.exists()) {
            directory.mkdirs();
        }
    }

    private static void convertFile(String csvPath, String arffPath, String relationName) throws Exception {
        CSVLoader csvLoader = new CSVLoader();
        csvLoader.setSource(new File(csvPath));

        // Configurazione specifica per il formato CSV
        csvLoader.setFieldSeparator(",");
        csvLoader.setNoHeaderRowPresent(false);

        Instances data = csvLoader.getDataSet();
        data.setRelationName(relationName);

        // Remove columns 1-3
        Remove removeFilter = new Remove();
        removeFilter.setAttributeIndices("1-3");
        removeFilter.setInputFormat(data);
        Instances filteredData = Filter.useFilter(data, removeFilter);

        ArffSaver arffSaver = new ArffSaver();
        arffSaver.setInstances(filteredData);
        arffSaver.setFile(new File(arffPath));
        arffSaver.writeBatch();
    }
}
