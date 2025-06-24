package project.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import weka.core.Instances;
import weka.core.converters.ArffSaver;
import weka.core.converters.CSVLoader;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Remove;
import weka.filters.unsupervised.attribute.StringToNominal;
import java.io.File;
import java.nio.file.Path;



public class CSVtoARFFConverter {
    private static final Logger LOGGER = LoggerFactory.getLogger("CSVtoARFFConverter");
    private CSVtoARFFConverter() {
    }

    public static void executeConversion(String projectName, int numOFRelease) {
        Path csvPath = project.utils.ConstantsWindowsFormat.CSV_PATH;
        Path testCsvPath = project.utils.ConstantsWindowsFormat.TEST_CSV_PATH;
        Path arffCsvPath=  project.utils.ConstantsWindowsFormat.ARFF_PATH;

        for (int i = 2; i < numOFRelease; i++) {
            try {
                // Verifica esistenza directory
                createDirectoryIfNotExists(String.valueOf(csvPath));
                createDirectoryIfNotExists(String.valueOf(testCsvPath));

                Path csvFilePathTrain = csvPath.resolve(projectName.toUpperCase() + "Train" + i + ".csv");
                Path csvFilePathTest = testCsvPath.resolve(projectName.toUpperCase() + "Test" + (i+1) + ".csv");

                Path arffFilePathTrain = arffCsvPath.resolve(projectName + "_Train_R" + i + ".arff");
                Path arffFilePathTest = arffCsvPath.resolve(projectName + "_Test_R" + i + ".arff");

                // Verifica esistenza file
                if (!new File(String.valueOf(csvFilePathTrain)).exists() || !new File(String.valueOf(csvFilePathTest)).exists()) {
                    LOGGER.error("File CSV mancante per la Release {}", i);
                    continue;
                }

                // Configurazione per il training set
                convertFile(String.valueOf(csvFilePathTrain), String.valueOf(arffFilePathTrain), projectName + "_Train_R" + i);

                // Configurazione per il test set
                convertFile(String.valueOf(csvFilePathTest), String.valueOf(arffFilePathTest), projectName + "_Test_R" + i);

                LOGGER.info("Conversione completata per Release {} " , i);

            } catch (Exception e) {
               LOGGER.error("Errore durante la conversione della Release {} : {}", i, e.getMessage());
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
        csvLoader.setFieldSeparator(",");
        csvLoader.setNoHeaderRowPresent(false);

        Instances data = csvLoader.getDataSet();
        data.setRelationName(relationName);

        // Rimuoviamo solo method e path (colonne 2-3)
        Remove removeFilter = new Remove();
        removeFilter.setAttributeIndices("2-3");
        removeFilter.setInputFormat(data);
        Instances filteredData = Filter.useFilter(data, removeFilter);

        // Converti l'attributo release in nominale
        StringToNominal stringToNominal = new StringToNominal();
        stringToNominal.setAttributeRange("1"); // Prima colonna (release)
        stringToNominal.setInputFormat(filteredData);
        Instances finalData = Filter.useFilter(filteredData, stringToNominal);

        ArffSaver arffSaver = new ArffSaver();
        arffSaver.setInstances(finalData);
        arffSaver.setFile(new File(arffPath));
        arffSaver.writeBatch();
    }
}
