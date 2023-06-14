package project.controllers;
import weka.core.Instances;
import weka.core.converters.CSVLoader;
import weka.core.converters.ArffSaver;

import java.io.File;

import static java.lang.System.*;

public class CSVtoARFFConverter {

    private CSVtoARFFConverter() {
    }

    public static void executeConversion(String projectName, int numOFRelease) {

        for (int i = 2; i < numOFRelease; i++) {

            String csvFilePathTrain = projectName + "_Train_Release_" + i + ".csv";
            String csvFilePathTest = projectName + "_Test_Release_" + (i + 1) + ".csv";

            String arffFilePathTrain = projectName + "_Train_R" + i + ".arff";
            String arffFilePathTest = projectName + "_Test_R" + i + ".arff";

            try {
                //Carico il file csv di train
                CSVLoader csvLoader = new CSVLoader();
                csvLoader.setSource(new File(csvFilePathTrain));
                Instances data = csvLoader.getDataSet();

                //Salva il file ARFF di train
                ArffSaver arffSaver = new ArffSaver();
                arffSaver.setInstances(data);
                arffSaver.setFile(new File(arffFilePathTrain));
                arffSaver.writeBatch();

                //Carico il file csv di test
                csvLoader.reset();
                csvLoader.setSource(new File(csvFilePathTest));
                data = csvLoader.getDataSet();

                //Salva il file ARFF di test
                arffSaver.setInstances(data);
                arffSaver.setFile(new File(arffFilePathTest));
                arffSaver.writeBatch();

            } catch (Exception e) {
                err.println("Si è verificato un errore durante la conversione: " + e.getMessage());
            }
        }
    }
}


