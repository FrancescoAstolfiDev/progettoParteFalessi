package project.controllers;

import project.models.DataSetType;
import project.models.ResultsHolder;
import project.statefull.ConstantSize;
import project.statefull.ConstantsWindowsFormat;
import project.utils.CostumException;
import project.utils.Projects;
import weka.classifiers.Classifier;
import weka.filters.supervised.attribute.AttributeSelection;
import weka.attributeSelection.CfsSubsetEval;
import weka.attributeSelection.GreedyStepwise;
import weka.classifiers.CostMatrix;
import weka.classifiers.Evaluation;
import weka.classifiers.functions.MultilayerPerceptron;
import weka.core.Attribute;
import weka.classifiers.functions.SGD;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.core.converters.ConverterUtils.DataSource;
import weka.filters.Filter;
import weka.filters.supervised.instance.Resample;
import weka.filters.supervised.instance.SpreadSubsample;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import static java.lang.System.*;

public class EvaluationFlow {

    // Messaggi di log come variabili di classe
    private static final String THREAD_POOL_MSG = "Using thread pool with %d threads for parallel processing";
    private static final String ERROR_PROCESSING_RELEASE_MSG = "Error processing release %d: %s";
    private static final String ERROR_LOADING_ARFF_MSG = "Error loading ARFF files for release %d: %s";
    private static final String WARNING_EMPTY_TRAIN_SET_MSG = "Warning: Empty training set for release %d. Skipping %s evaluation.";
    private static final String WARNING_EMPTY_TEST_SET_MSG = "Warning: Empty test set for release %d. Skipping %s evaluation.";
    private static final String WARNING_INVALID_CLASS_INDEX_MSG = "Warning: Invalid class index in %s set for release %d. Setting to last attribute.";
    private static final String WARNING_NOT_ENOUGH_INSTANCES_MSG = "Warning: Not enough instances for %s training in release %d%s";
    private static final String ERROR_CLASSIFIER_EVALUATION_MSG = "Error in %s evaluation for release %d: %s";
    private static final String WARNING_UNDERSAMPLING_RESULT_MSG = "Warning: Under-sampling resulted in empty dataset for release %d. Skipping.";
    private static final String WARNING_OVERSAMPLING_RESULT_MSG = "Warning: Oversampling resulted in empty dataset for release %d. Skipping.";
    private static final String WARNING_FEATURE_SELECTION_RESULT_MSG = "Warning: Feature selection resulted in %s for release %d. Skipping.";
    private static final String WARNING_TOO_FEW_ATTRIBUTES_MSG = "Warning: Feature selection resulted in too few attributes for release %d. Skipping.";
    private static final String FEATURE_SELECTION_LOG_MSG = "\n=== Feature Selection Results for Release %d ===\n";
    private static final String CSV_SUCCESS_MSG = "File CSV creato con successo.";
    private static final String CSV_ERROR_MSG = "Si è verificato un errore durante la creazione del file CSV: %s";
    private static final String RELEASE_NUMBER_WARNING_MSG = "Warning: Could not extract release number from relation name: %s";
    private static final String CACHED_FILTER_MSG = "Using cached feature selection filter for release %d";
    private static final String NEW_FILTER_MSG = "Creating new feature selection filter%s";
    private static final String FEATURE_LOG_WRITTEN_MSG = "Feature selection log written to %s";
    private static final String FEATURE_LOG_ERROR_MSG = "Error writing feature selection log to file: %s";
    private static final String EXPENSIVE_FEATURE_SELECTION="expensive feature selection";
    private static final String FEATURE_SELECTION="feature selection";
    private static final String OVERSAMPLING="oversampling";
    private static final String UNDER_SAMPLING="under sampling";
    private static final String ERROR_RF_COST_SENSITIVE_EVALUATION_MSG = "Error in RF cost-sensitive evaluation for release %d: %s";
    private static final String EMPTY_TEST_DATASET_MSG = "empty test dataset";
    private static final String EMPTY_TRAINING_DATASET_MSG = "empty training dataset";
    private static final String USING_DEFAULT_100_PERCENT = ". Using default 100%.";
    private static final String FORMAT_6_4F = "%6.4f %n";
    private static final String FALSE_LITERAL = "false";
    private static final String UNKNOWN_LITERAL = "unknown";

    RandomForest randomForestClassifier;
    MultilayerPerceptron multilayerPerceptronClassifier;
    SGD sgdClassifier;
    // Cache for feature selection filters to avoid redundant computation
    private final java.util.Map<Integer, AttributeSelection> featureSelectionCache = new java.util.HashMap<>();
    // Map to store feature selection IDs
    private final java.util.Map<Integer, java.util.Map<String, String>> featureSelectionIdCache = new java.util.HashMap<>();
    List<ResultsHolder> standardRFList;
    List<ResultsHolder> standardNBList;
    List<ResultsHolder> standardSGDList;
    List<ResultsHolder> costSensitiveRFList;
    List<ResultsHolder> costSensitiveSGDList;
    List<ResultsHolder> costSensitiveNBList;
    List<ResultsHolder> underSamplRFList;
    List<ResultsHolder> underSamplSGDList;
    List<ResultsHolder> underSamplNBList;
    List<ResultsHolder> overSamplRFList;
    List<ResultsHolder> overSamplSGDList;
    List<ResultsHolder> overSamplNBList;
    List<ResultsHolder> featSelRFList;
    List<ResultsHolder> featSelSGDList;
    List<ResultsHolder> featSelNBList;
    List<ResultsHolder> featSelUnderSamplRFList;
    List<ResultsHolder> featSelUnderSamplSGDList;
    List<ResultsHolder> featSelUnderSamplNBList;
    List<ResultsHolder> featSelCostSensRFList;
    List<ResultsHolder> featSelCostSensSGDList;
    List<ResultsHolder> featSelCostSensNBList;

    public EvaluationFlow(){
        //questi sono i classificatori che utilizzo con parametri ottimizzati per velocità
        // Create RandomForest with default settings similar to Weka example
        this.randomForestClassifier = new RandomForest();
        // Print available options for RandomForest
        try {
            java.util.Enumeration<weka.core.Option> options = this.randomForestClassifier.listOptions();
            out.println("\n=== Available RandomForest Options ===");
            while (options.hasMoreElements()) {
                weka.core.Option option = options.nextElement();
                out.println(option.synopsis() + "\t" + option.description());
            }
            out.println("=====================================\n");
        } catch (Exception e) {
            out.println("Error listing RandomForest options: " + e.getMessage());
        }

        // Configure RandomForest with minimal settings
        this.randomForestClassifier.setNumIterations(100); // I parameter - number of iterations
        this.randomForestClassifier.setNumExecutionSlots(1); // num-slots parameter - single-threaded execution

        this.multilayerPerceptronClassifier = new MultilayerPerceptron();
        // Configure MultilayerPerceptron for optimal performance
        this.multilayerPerceptronClassifier.setLearningRate(0.1);
        this.multilayerPerceptronClassifier.setMomentum(0.2);
        this.multilayerPerceptronClassifier.setTrainingTime(500); // Limit training time
        this.multilayerPerceptronClassifier.setHiddenLayers("3"); // Simple network architecture

        this.sgdClassifier = new SGD();
        // Configure SGD for optimal performance
        this.sgdClassifier.setLearningRate(0.01);
        this.sgdClassifier.setEpochs(500); // Number of epochs

        //queste sono le liste che contengono i risultati delle valutazioni per tipologia di classificatore
        this.standardRFList= new ArrayList<>();
        this.standardNBList= new ArrayList<>();
        this.standardSGDList = new ArrayList<>();
        this.costSensitiveRFList = new ArrayList<>();
        this.costSensitiveSGDList = new ArrayList<>();
        this.costSensitiveNBList = new ArrayList<>();
        this.underSamplRFList = new ArrayList<>();
        this.underSamplSGDList = new ArrayList<>();
        this.underSamplNBList = new ArrayList<>();
        this.overSamplRFList = new ArrayList<>();
        this.overSamplSGDList = new ArrayList<>();
        this.overSamplNBList = new ArrayList<>();
        this.featSelSGDList = new ArrayList<>();
        this.featSelNBList = new ArrayList<>();
        this.featSelRFList = new ArrayList<>();
        this.featSelUnderSamplSGDList = new ArrayList<>();
        this.featSelUnderSamplNBList = new ArrayList<>();
        this.featSelUnderSamplRFList = new ArrayList<>();
        this.featSelCostSensSGDList = new ArrayList<>();
        this.featSelCostSensNBList = new ArrayList<>();
        this.featSelCostSensRFList = new ArrayList<>();
    }

    public void executeFlow() {
        try {
            int numRelease = determineNumRelease();
            int threadPoolSize = determineThreadPoolSize();
            out.printf((THREAD_POOL_MSG) + "%n", threadPoolSize);

            try (ExecutorServiceWithAwait executor = new ExecutorServiceWithAwait(threadPoolSize)) {
                List<CompletableFuture<Void>> futures = processReleases(executor, numRelease);
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
            }
            writeResults();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Ripristina lo stato di interruzione
            throw new CostumException("Interruzione durante l'evaluation flow", e);
        } catch (ExecutionException e) {
            throw new CostumException("Errore durante l'evaluation flow", e);
        }
    }


    private int determineNumRelease() {
        return Projects.getNumStepDatasetStatic();
    }

    private int determineThreadPoolSize() {
        int processors = Math.min(ConstantSize.NUM_THREADS,Runtime.getRuntime().availableProcessors()) ;
        return Math.max(2, processors - 1);
    }

    private List<CompletableFuture<Void>> processReleases(ExecutorServiceWithAwait executor, int numRelease) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 3; i <= numRelease; i++) {
            final int releaseIndex = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    processRelease(releaseIndex);
                } catch (Exception e) {
                    out.println(String.format(ERROR_PROCESSING_RELEASE_MSG, releaseIndex, e.getMessage()));
                }
            }, executor.getExecutor());
            futures.add(future);
        }
        return futures;
    }


    private void writeResults() {
        List<List<ResultsHolder>> allResults = Arrays.asList(
                standardRFList, standardNBList, standardSGDList,
                costSensitiveRFList, costSensitiveSGDList, costSensitiveNBList,
                underSamplRFList, underSamplSGDList, underSamplNBList,
                overSamplRFList, overSamplSGDList, overSamplNBList,
                featSelRFList, featSelSGDList, featSelNBList,
                featSelUnderSamplRFList, featSelUnderSamplSGDList, featSelUnderSamplNBList,
                featSelCostSensRFList, featSelCostSensSGDList, featSelCostSensNBList
        );
        csvWriter(allResults);
    }

    private static class ExecutorServiceWithAwait implements AutoCloseable {
        private final ExecutorService executor;

        ExecutorServiceWithAwait(int threadPoolSize) {
            this.executor = Executors.newFixedThreadPool(threadPoolSize);
        }

        ExecutorService getExecutor() {
            return executor;
        }

        @Override
        public void close() {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // Helper method to process a single release
    private void processRelease(int releaseIndex) throws Exception {
        //recupero i dati dai file .arff
        String trainFileName = Projects.getTrainProject() + "_Train_R" + releaseIndex + ".arff";
        String testFileName =Projects.getTestProject() + "_Test_R" + releaseIndex + ".arff";
        String trainFilePath = ConstantsWindowsFormat.ARFF_PATH.resolve(trainFileName).toString();
        String testFilePath = ConstantsWindowsFormat.ARFF_PATH.resolve(testFileName).toString();

        try {
            DataSource trainSource = new DataSource(trainFilePath);
            DataSource testSource = new DataSource(testFilePath);
            Instances trainSet = trainSource.getDataSet();
            Instances testSet = testSource.getDataSet();

            //setto il parametro buggy come variabile di interesse
            trainSet.setClassIndex(trainSet.numAttributes() - 1);
            testSet.setClassIndex(testSet.numAttributes() - 1);

            // Process all evaluation methods for this release
            // We could parallelize these too, but they share classifiers which might not be thread-safe
            evalStandard(trainSet, testSet, releaseIndex, false, false, false);
            evalCostSensitive(trainSet, testSet, releaseIndex, false);
            evalUnderSampling(trainSet, testSet, releaseIndex);
            evalOverSampling(trainSet, testSet, releaseIndex);
            evalFeatureSelection(trainSet, testSet, releaseIndex);
            evalUnderSampFeatureSelection(trainSet, testSet, releaseIndex);
            evalOverSampFeatureSelection(trainSet, testSet, releaseIndex);
            evalCostFeatureSelection(trainSet, testSet, releaseIndex);
            evalCostUnderSampling(trainSet, testSet, releaseIndex);
            evalCostOverSampling(trainSet, testSet, releaseIndex);
            evalCostUnderSampFeatureSelection(trainSet, testSet, releaseIndex);
            evalCostOverSampFeatureSelection(trainSet, testSet, releaseIndex);
        } catch (Exception e) {
            out.println(String.format(ERROR_LOADING_ARFF_MSG, releaseIndex, e.getMessage()));
            throw e; // Rethrow to be caught by the calling method
        }
    }

    //metodo che addestra i classificatori in maniera standard, ovvero senza sampling, feature selection o
    //cost sensitive. Effettua un passo del walk forward per i tre classificatori
    public void evalStandard(Instances trainSet, Instances testSet, int index, boolean isFeatureSelected,
                             boolean isUnderSampled, boolean isOverSampled) {

        if (!validateDatasets(trainSet, testSet, index)) {
            return;
        }

        ensureValidClassIndices(trainSet, testSet);

        // Crea e configura i classificatori thread-local
        Map<String, Classifier> threadLocalClassifiers = createThreadLocalClassifiers();

        // Esegui training e valutazione in parallelo
        List<CompletableFuture<ResultsHolder>> futures = trainAndEvaluateClassifiers(
                threadLocalClassifiers, trainSet, testSet, index, isFeatureSelected, isUnderSampled);

        // Raccogli i risultati
        List<ResultsHolder> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        // Salva i risultati nelle liste appropriate
        saveResults(results, isFeatureSelected, isUnderSampled, isOverSampled);
    }

    private boolean validateDatasets(Instances trainSet, Instances testSet, int index) {
        if (trainSet == null || trainSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TRAIN_SET_MSG, index, "standard"));
            return false;
        }
        if (testSet == null || testSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TEST_SET_MSG, index, "standard"));
            return false;
        }
        return true;
    }

    private void ensureValidClassIndices(Instances trainSet, Instances testSet) {
        if (trainSet.classIndex() < 0 || trainSet.classIndex() >= trainSet.numAttributes()) {
            trainSet.setClassIndex(trainSet.numAttributes() - 1);
        }
        if (testSet.classIndex() < 0 || testSet.classIndex() >= testSet.numAttributes()) {
            testSet.setClassIndex(testSet.numAttributes() - 1);
        }
    }

    private Map<String, Classifier> createThreadLocalClassifiers() {
        Map<String, Classifier> classifiers = new HashMap<>();

        // Create RandomForest with the same configuration as the main classifier
        RandomForest rf = new RandomForest();
        rf.setNumIterations(this.randomForestClassifier.getNumIterations());
        rf.setNumExecutionSlots(1);

        // Print the options that will be used for this evaluation
        out.println("\n=== RandomForest Configuration for Evaluation ===");
        out.println("NumIterations: " + rf.getNumIterations());
        out.println("NumExecutionSlots: " + rf.getNumExecutionSlots());
        out.println("=================================================\n");

        classifiers.put("rf", rf);

        SGD sgd = new SGD();
        sgd.setLearningRate(this.sgdClassifier.getLearningRate());
        sgd.setEpochs(this.sgdClassifier.getEpochs());
        classifiers.put("sgd", sgd);

        MultilayerPerceptron mlp = new MultilayerPerceptron();
        mlp.setLearningRate(this.multilayerPerceptronClassifier.getLearningRate());
        mlp.setMomentum(this.multilayerPerceptronClassifier.getMomentum());
        mlp.setTrainingTime(this.multilayerPerceptronClassifier.getTrainingTime());
        mlp.setHiddenLayers(this.multilayerPerceptronClassifier.getHiddenLayers());
        classifiers.put("mlp", mlp);

        return classifiers;
    }

    private List<CompletableFuture<ResultsHolder>> trainAndEvaluateClassifiers(
            Map<String, Classifier> classifiers, Instances trainSet, Instances testSet,
            int index, boolean isFeatureSelected, boolean isUnderSampled) {

        return classifiers.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(() ->
                        evaluateClassifier(entry.getKey(), entry.getValue(), trainSet, testSet,
                                index, isFeatureSelected, isUnderSampled)))
                .toList();
    }
    private ResultsHolder evaluateClassifier(String classifierType, Classifier classifier,
                                             Instances trainSet, Instances testSet, int index,
                                             boolean isFeatureSelected, boolean isUnderSampled) {

        ResultsHolder defaultResult = new ResultsHolder(index, classifierType,
                isFeatureSelected, isUnderSampled, false);

        try {
            if (trainSet.numInstances() < 2) {
                out.println(String.format(WARNING_NOT_ENOUGH_INSTANCES_MSG,
                        classifierType.toUpperCase(), index, ""));
                return defaultResult;
            }

            classifier.buildClassifier(trainSet);
            Evaluation eval = new Evaluation(trainSet);
            eval.evaluateModel(classifier, testSet);

            if ("rf".equals(classifierType)) {
                displayDetailedEvaluation(eval, trainSet, testSet, classifier, index);
            }

            populateResults(defaultResult, eval, trainSet);
            return defaultResult;

        } catch (Exception e) {
            handleClassifierException(e, classifierType, index);
            return defaultResult;
        }
    }

// Nuovi metodi da aggiungere alla classe

    private void handleClassifierException(Exception e, String classifierType, int index) {
        if ("rf".equals(classifierType)) {
            handleRandomForestException(e, index);
        } else {
            logGeneralClassifierError(classifierType, index, e);
        }
    }

    private void handleRandomForestException(Exception e, int index) {
        if (e instanceof ArrayIndexOutOfBoundsException) {
            handleArrayIndexError(e, index);
        } else {
            logRandomForestError(e, index);
        }
        out.println("Stack trace for RF evaluation error:");
        e.printStackTrace(out);
    }

    private void handleArrayIndexError(Exception e, int index) {
        try {
            String[] parts = e.getMessage().split(" ");
            String indexValue = parts.length > 0 ? parts[0] : UNKNOWN_LITERAL;
            String lengthValue = parts.length > 5 ? parts[5] : UNKNOWN_LITERAL;
            out.println(String.format("Error in RF evaluation for release %d: Index %s out of bounds for length %s",
                    index, indexValue, lengthValue));
        } catch (Exception parseEx) {
            logSimpleRandomForestError(e, index);
        }
    }

    private void logRandomForestError(Exception e, int index) {
        String message = e.getMessage() != null ? e.getMessage() : "null";
        out.println(String.format("Error in RF evaluation for release %d: %s", index, message));
    }

    private void logSimpleRandomForestError(Exception e, int index) {
        out.println(String.format("Error in RF evaluation for release %d: %s",
                index, e.getMessage()));
    }

    private void logGeneralClassifierError(String classifierType, int index, Exception e) {
        out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG,
                classifierType.toUpperCase(), index, e.getMessage()));
    }



    private void populateResults(ResultsHolder results, Evaluation eval, Instances trainSet) {
        try {
            if (trainSet.classAttribute().numValues() > 1) {
                // Check if class index 1 is valid for this evaluation
                int classIndex = Math.min(1, trainSet.classAttribute().numValues() - 1);
                results.setAuc(eval.areaUnderROC(classIndex));
                results.setPrecision(eval.precision(classIndex));
                results.setRecall(eval.recall(classIndex));
            }
            results.setKappa(eval.kappa());
        } catch (Exception e) {
            // Silently handle any exceptions during metrics calculation
            // This prevents index out of bounds errors from being displayed
        }
    }

    /**
     * Displays detailed evaluation results similar to Weka's output format.
     * This includes confusion matrix, precision, recall, F-measure, etc.
     * 
     * @param eval The evaluation object
     * @param trainSet The training dataset
     * @param testSet The test dataset
     * @param classifier The classifier being evaluated
     * @param index The release index
     */
    private void displayDetailedEvaluation(Evaluation eval, Instances trainSet, Instances testSet, 
                                          Classifier classifier, int index) {
        try {
            StringBuilder sb = new StringBuilder();

            // Display run information
            sb.append("\n=== Run information for release ").append(index).append(" ===\n\n");
            sb.append("Scheme:       ").append(classifier.getClass().getName()).append("\n");
            sb.append("Relation:     ").append(trainSet.relationName()).append("\n");
            sb.append("Instances:    ").append(trainSet.numInstances()).append("\n");
            sb.append("Attributes:   ").append(trainSet.numAttributes()).append("\n");

            // List attributes
            for (int i = 0; i < trainSet.numAttributes(); i++) {
                sb.append("              ").append(trainSet.attribute(i).name()).append("\n");
            }

            sb.append("Test mode:    user supplied test set\n");
            sb.append("              ").append(testSet.numInstances()).append(" instances\n\n");

            // Display classifier model
            sb.append("=== Classifier model (full training set) ===\n\n");
            sb.append(classifier.toString()).append("\n\n");

            // Display evaluation results
            sb.append("=== Evaluation on test set ===\n\n");

            // Summary statistics
            sb.append("=== Summary === %n%n");
            sb.append("Correctly Classified Instances       ");
            sb.append(String.format("%5d     %6.4f%% %n", (int) eval.correct(), eval.pctCorrect()));
            sb.append("Incorrectly Classified Instances     ");
            sb.append(String.format("%5d     %6.4f%% %n", (int) eval.incorrect(), eval.pctIncorrect()));
            sb.append("Kappa statistic                      ");
            sb.append(String.format(FORMAT_6_4F, eval.kappa()));
            sb.append("Mean absolute error                  ");
            sb.append(String.format(FORMAT_6_4F, eval.meanAbsoluteError()));
            sb.append("Root mean squared error              ");
            sb.append(String.format(FORMAT_6_4F, eval.rootMeanSquaredError()));
            sb.append("Total Number of Instances            ");
            sb.append(String.format("%5d %n%n", testSet.numInstances()));

            // Detailed accuracy by class
            sb.append("=== Detailed Accuracy By Class === %n%n");
            sb.append(" TP Rate  FP Rate  Precision  Recall   F-Measure  MCC      ROC Area  PRC Area  Class\n");

            for (int i = 0; i < trainSet.classAttribute().numValues(); i++) {
                sb.append(String.format(" %.3f    %.3f    %.3f      %.3f    %.3f      %.3f    %.3f    %.3f    %s %n%n",
                        eval.truePositiveRate(i),
                        eval.falsePositiveRate(i),
                        eval.precision(i),
                        eval.recall(i),
                        eval.fMeasure(i),
                        eval.matthewsCorrelationCoefficient(i),
                        eval.areaUnderROC(i),
                        eval.areaUnderPRC(i),
                        trainSet.classAttribute().value(i)));
            }

            // Weighted average
            sb.append(String.format("Weighted Avg.    %.3f    %.3f    %.3f      %.3f    %.3f      %.3f    %.3f    %.3f %n%n",
                    eval.weightedTruePositiveRate(),
                    eval.weightedFalsePositiveRate(),
                    eval.weightedPrecision(),
                    eval.weightedRecall(),
                    eval.weightedFMeasure(),
                    eval.weightedMatthewsCorrelation(),
                    eval.weightedAreaUnderROC(),
                    eval.weightedAreaUnderPRC()));

            // Confusion matrix
            sb.append("=== Confusion Matrix ===\n\n");

            double[][] confusionMatrix = eval.confusionMatrix();
            sb.append("    ");
            for (int i = 0; i < confusionMatrix.length; i++) {
                sb.append(String.format("%5s", (char)('a' + i)));
            }
            sb.append("   <-- classified as\n");

            for (int i = 0; i < confusionMatrix.length; i++) {
                sb.append(String.format("%5s", (char)('a' + i)));
                for (int j = 0; j < confusionMatrix[i].length; j++) {
                    sb.append(String.format("%5.0f", confusionMatrix[i][j]));
                }
                sb.append("   |    ").append((char)('a' + i)).append(" = ");
                sb.append(trainSet.classAttribute().value(i)).append("\n");
            }

            // Print the detailed evaluation
            out.println(sb.toString());

        } catch (Exception e) {
            out.println("Error displaying detailed evaluation: " + e.getMessage());
        }
    }

    private void saveResults(List<ResultsHolder> results, boolean isFeatureSelected,
                             boolean isUnderSampled, boolean isOverSampled) {
        ResultsHolder rf = results.get(0);
        ResultsHolder sgd = results.get(1);
        ResultsHolder mlp = results.get(2);

        List<List<ResultsHolder>> targetLists = determineTargetLists(
                isFeatureSelected, isUnderSampled, isOverSampled);

        targetLists.get(0).add(rf);
        targetLists.get(1).add(sgd);
        targetLists.get(2).add(mlp);
    }

    private List<List<ResultsHolder>> determineTargetLists(boolean isFeatureSelected,
                                                           boolean isUnderSampled, boolean isOverSampled) {

        if (isUnderSampled && isFeatureSelected) {
            return Arrays.asList(featSelUnderSamplRFList, featSelUnderSamplSGDList,
                    featSelUnderSamplNBList);
        } else if (isUnderSampled) {
            return Arrays.asList(underSamplRFList, underSamplSGDList, underSamplNBList);
        } else if (isOverSampled) {
            return Arrays.asList(overSamplRFList, overSamplSGDList, overSamplNBList);
        } else if (isFeatureSelected) {
            return Arrays.asList(featSelRFList, featSelSGDList, featSelNBList);
        } else {
            return Arrays.asList(standardRFList, standardSGDList, standardNBList);
        }
    }
    public void evalCostSensitive(Instances trainSet, Instances testSet, int index, boolean isFeatureSelected)  {
        if (!validateDatasets(trainSet, testSet, index)) {
            return;
        }

        ensureValidClassIndices(trainSet, testSet);

        try {
            CostSensitiveClassifier costSensitiveClassifier = createCostSensitiveClassifier();
            Map<String, Classifier> threadLocalClassifiers = createThreadLocalClassifiers();

            List<ResultsHolder> results = evaluateAllClassifiers(
                    costSensitiveClassifier, threadLocalClassifiers, trainSet, testSet, index, isFeatureSelected);

            // Aggiunto false per isUnderSampled e isOverSampled poiché non applicabili per cost-sensitive
            saveResults(results, isFeatureSelected, false, false);

        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, "cost-sensitive", index, e.getMessage()));
        }
    }

    private CostSensitiveClassifier createCostSensitiveClassifier() {
        CostSensitiveClassifier classifier = new CostSensitiveClassifier();
        CostMatrix matrix = new CostMatrix(2);
        matrix.setCell(0, 0, 0.0);
        matrix.setCell(1, 1, 0.0);
        matrix.setCell(0, 1, 1.0);
        matrix.setCell(1, 0, 10.0);
        classifier.setCostMatrix(matrix);
        classifier.setMinimizeExpectedCost(true);
        return classifier;
    }
    private List<ResultsHolder> evaluateAllClassifiers(
            CostSensitiveClassifier costSensitiveClassifier,
            Map<String, Classifier> classifiers,
            Instances trainSet,
            Instances testSet,
            int index,
            boolean isFeatureSelected) {

        return classifiers.entrySet().stream()
                .map(entry -> evaluateSingleClassifier(
                        costSensitiveClassifier,
                        entry.getKey(),
                        entry.getValue(),
                        trainSet,
                        testSet,
                        index,
                        isFeatureSelected))
                .toList();
    }
    private ResultsHolder evaluateSingleClassifier(
            CostSensitiveClassifier costSensitiveClassifier,
            String classifierType,
            Classifier baseClassifier,
            Instances trainSet,
            Instances testSet,
            int index,
            boolean isFeatureSelected) {

        ResultsHolder defaultResult = new ResultsHolder(index, classifierType, isFeatureSelected, false, true);

        try {
            return executeEvaluation(costSensitiveClassifier, classifierType, baseClassifier,
                    trainSet, testSet, index, defaultResult);
        } catch (Exception e) {
            handleEvaluationException(e, classifierType, index);
            return defaultResult;
        }
    }

    private ResultsHolder executeEvaluation(
            CostSensitiveClassifier costSensitiveClassifier,
            String classifierType,
            Classifier baseClassifier,
            Instances trainSet,
            Instances testSet,
            int index,
            ResultsHolder defaultResult) throws Exception {

        costSensitiveClassifier.setClassifier(baseClassifier);
        costSensitiveClassifier.buildClassifier(trainSet);

        Evaluation eval = new Evaluation(trainSet);
        eval.evaluateModel(costSensitiveClassifier, testSet);

        if ("rf".equals(classifierType)) {
            out.println("\n=== Cost-Sensitive Evaluation ===");
            displayDetailedEvaluation(eval, trainSet, testSet, costSensitiveClassifier, index);
        }

        populateResults(defaultResult, eval, trainSet);
        return defaultResult;
    }

    private void handleEvaluationException(Exception e, String classifierType, int index) {
        if (!"rf".equals(classifierType)) {
            logNonRFError(classifierType, index, e);
            return;
        }

        handleRFError(e, index);
    }

    private void handleRFError(Exception e, int index) {
        if (e instanceof ArrayIndexOutOfBoundsException) {
            logArrayIndexError(e, index);
        } else {
            logGeneralRFError(e, index);
        }

        out.println("Stack trace for RF cost-sensitive evaluation error:");
        e.printStackTrace(out);
    }

    private void logArrayIndexError(Exception e, int index) {
        try {
            String[] parts = e.getMessage().split(" ");
            String indexValue = parts.length > 0 ? parts[0] : UNKNOWN_LITERAL;
            String lengthValue = parts.length > 5 ? parts[5] : UNKNOWN_LITERAL;
            out.println(String.format("Error in RF cost-sensitive evaluation for release %d: Index %s out of bounds for length %s",
                    index, indexValue, lengthValue));
        } catch (Exception parseEx) {
            out.println(String.format(ERROR_RF_COST_SENSITIVE_EVALUATION_MSG, index, e.getMessage()));
        }
    }

    private void logGeneralRFError(Exception e, int index) {
        if (e.getMessage() != null) {
            out.println(String.format(ERROR_RF_COST_SENSITIVE_EVALUATION_MSG, index, e.getMessage()));
        } else {
            out.println(String.format("Error in RF cost-sensitive evaluation for release %d: null", index));
        }
    }

    private void logNonRFError(String classifierType, int index, Exception e) {
        out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG,
                classifierType.toUpperCase() + " cost-sensitive", index, e.getMessage()));
    }







    //metodo che addestra i classificatori con under sampling.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalUnderSampling(Instances trainSet, Instances testSet, int index) throws Exception {
        // Validate datasets before processing
        if (trainSet == null || trainSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TRAIN_SET_MSG, index, UNDER_SAMPLING));
            return;
        }

        if (testSet == null || testSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TEST_SET_MSG, index, UNDER_SAMPLING));
            return;
        }

        // Ensure class attribute is set and valid
        if (trainSet.classIndex() < 0 || trainSet.classIndex() >= trainSet.numAttributes()) {
            out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, DataSetType.TRAINING, index));
            trainSet.setClassIndex(trainSet.numAttributes() - 1);
        }

        try {
            SpreadSubsample filter = new SpreadSubsample();
            filter.setInputFormat(trainSet);
            filter.setDistributionSpread(1.0);
            Instances underSampledSet = Filter.useFilter(trainSet, filter);

            // Validate the under-sampled set before proceeding
            if (underSampledSet == null || underSampledSet.numInstances() == 0) {
                out.println(String.format(WARNING_UNDERSAMPLING_RESULT_MSG, index));
                return;
            }

            evalStandard(underSampledSet, testSet, index, false, true, false);
        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, "under-sampling", index, e.getMessage()));
            // Continue with execution rather than throwing the exception
        }
    }

    //metodo che addestra i classificatori con over sampling.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalOverSampling(Instances trainSet, Instances testSet, int index) throws Exception {
        // Validate dataset before processing
        if (trainSet == null || trainSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TRAIN_SET_MSG, index, OVERSAMPLING));
            return;
        }

        try {
            Resample filter = new Resample();
            filter.setBiasToUniformClass(1.0);
            filter.setNoReplacement(false);

            int numAllInstances = trainSet.numInstances();
            int classMajorIndex = trainSet.classAttribute().indexOfValue(FALSE_LITERAL);
            int numMajorInstances = 0;

            // Check if "false" value exists in the class attribute
            if (classMajorIndex == -1) {
                // If "false" doesn't exist, assume the first value is the majority class
                out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, DataSetType.TRAINING, index));
                classMajorIndex = 0;
            }

            for (int i = 0; i < numAllInstances; i++) {
                if (trainSet.instance(i).classValue() == classMajorIndex) {
                    numMajorInstances++;
                }
            }

            // Ensure we have a positive sample size to avoid "bound must be positive" errors
            double sampleSize = ((double) numMajorInstances / numAllInstances) * 2 * 100;
            if (sampleSize <= 0) {
                out.println(String.format(WARNING_NOT_ENOUGH_INSTANCES_MSG,OVERSAMPLING, index, USING_DEFAULT_100_PERCENT));
                sampleSize = 100.0; // Default to 100% if calculation results in non-positive value
            }

            filter.setSampleSizePercent(sampleSize);
            filter.setInputFormat(trainSet);
            Instances overSampledSet = Filter.useFilter(trainSet, filter);

            // Validate the oversampled set before proceeding
            if (overSampledSet == null || overSampledSet.numInstances() == 0) {
                out.println(String.format(WARNING_OVERSAMPLING_RESULT_MSG, index));
                return;
            }

            evalStandard(overSampledSet, testSet, index, false, false, true);
        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, OVERSAMPLING, index, e.getMessage()));
            // Continue with execution rather than throwing the exception
        }
    }

    //metodo che addestra i classificatori con feature selection.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalFeatureSelection(Instances trainSet, Instances testSet, int index) throws Exception {
        // Validate datasets before processing
        if (trainSet == null || trainSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TRAIN_SET_MSG, index,FEATURE_SELECTION));
            return;
        }

        if (testSet == null || testSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TEST_SET_MSG, index, FEATURE_SELECTION));
            return;
        }

        // Ensure class attribute is set and valid
        if (trainSet.classIndex() < 0 || trainSet.classIndex() >= trainSet.numAttributes()) {
            out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, "training", index));
            trainSet.setClassIndex(trainSet.numAttributes() - 1);
        }

        if (testSet.classIndex() < 0 || testSet.classIndex() >= testSet.numAttributes()) {
            out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, "test", index));
            testSet.setClassIndex(testSet.numAttributes() - 1);
        }

        try {
            AttributeSelection filter = getFilter(trainSet);

            Instances filteredTrainSet = Filter.useFilter(trainSet, filter);
            Instances filteredTestSet = Filter.useFilter(testSet, filter);

            // Validate the filtered sets before proceeding
            if (filteredTrainSet == null || filteredTrainSet.numInstances() == 0) {
                out.println(String.format(WARNING_FEATURE_SELECTION_RESULT_MSG, EMPTY_TRAINING_DATASET_MSG, index));
                return;
            }

            if (filteredTestSet == null || filteredTestSet.numInstances() == 0) {
                out.println(String.format(WARNING_FEATURE_SELECTION_RESULT_MSG, EMPTY_TEST_DATASET_MSG, index));
                return;
            }

            // Ensure filtered datasets have at least one attribute plus class
            if (filteredTrainSet.numAttributes() < 2) {
                out.println(String.format(WARNING_TOO_FEW_ATTRIBUTES_MSG, index));
                return;
            }

            int numAttrFiltered = filteredTrainSet.numAttributes();
            filteredTrainSet.setClassIndex(numAttrFiltered - 1);
            filteredTestSet.setClassIndex(numAttrFiltered - 1);

            // Get the feature selection ID for this filtered dataset
            String featureSelectionId = getFeatureSelectionId(filteredTrainSet, index);

            // Pass the feature selection ID to evalStandard
            evalStandardWithFeatureSelectionId(filteredTrainSet, filteredTestSet, index, true, false, false, featureSelectionId);
        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, FEATURE_SELECTION, index, e.getMessage()));
            // Continue with execution rather than throwing the exception
        }
    }

    /**
     * Variant of evalStandard that also sets the feature selection ID.
     */
    public void evalStandardWithFeatureSelectionId(Instances trainSet, Instances testSet, int index, boolean isFeatureSelected,
                             boolean isUnderSampled, boolean isOverSampled, String featureSelectionId) {

        if (!validateDatasets(trainSet, testSet, index)) {
            return;
        }

        ensureValidClassIndices(trainSet, testSet);

        // Crea e configura i classificatori thread-local
        Map<String, Classifier> threadLocalClassifiers = createThreadLocalClassifiers();

        // Esegui training e valutazione in parallelo
        List<CompletableFuture<ResultsHolder>> futures = trainAndEvaluateClassifiersWithFeatureSelectionId(
                threadLocalClassifiers, trainSet, testSet, index, isFeatureSelected, isUnderSampled, featureSelectionId);

        // Raccogli i risultati
        List<ResultsHolder> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        // Salva i risultati nelle liste appropriate
        saveResults(results, isFeatureSelected, isUnderSampled, isOverSampled);
    }

    private List<CompletableFuture<ResultsHolder>> trainAndEvaluateClassifiersWithFeatureSelectionId(
            Map<String, Classifier> classifiers, Instances trainSet, Instances testSet,
            int index, boolean isFeatureSelected, boolean isUnderSampled, String featureSelectionId) {

        return classifiers.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(() -> {
                    EvaluationConfig config = new EvaluationConfig.Builder()
                            .classifierType(entry.getKey())
                            .classifier(entry.getValue())
                            .trainSet(trainSet)
                            .testSet(testSet)
                            .index(index)
                            .isFeatureSelected(isFeatureSelected)
                            .isUnderSampled(isUnderSampled)
                            .featureSelectionId(featureSelectionId)
                            .build();
                    return evaluateClassifierWithFeatureSelectionId(config);
                }))
                .toList();
    }

    public static class EvaluationConfig {
        private final String classifierType;
        private final Classifier classifier;
        private final Instances trainSet;
        private final Instances testSet;
        private final int index;
        private final boolean isFeatureSelected;
        private final boolean isUnderSampled;
        private final String featureSelectionId;

        private EvaluationConfig(Builder builder) {
            this.classifierType = builder.classifierType;
            this.classifier = builder.classifier;
            this.trainSet = builder.trainSet;
            this.testSet = builder.testSet;
            this.index = builder.index;
            this.isFeatureSelected = builder.isFeatureSelected;
            this.isUnderSampled = builder.isUnderSampled;
            this.featureSelectionId = builder.featureSelectionId;
        }

        public static class Builder {
            private String classifierType;
            private Classifier classifier;
            private Instances trainSet;
            private Instances testSet;
            private int index;
            private boolean isFeatureSelected;
            private boolean isUnderSampled;
            private String featureSelectionId;

            public Builder classifierType(String classifierType) {
                this.classifierType = classifierType;
                return this;
            }

            public Builder classifier(Classifier classifier) {
                this.classifier = classifier;
                return this;
            }

            public Builder trainSet(Instances trainSet) {
                this.trainSet = trainSet;
                return this;
            }

            public Builder testSet(Instances testSet) {
                this.testSet = testSet;
                return this;
            }

            public Builder index(int index) {
                this.index = index;
                return this;
            }

            public Builder isFeatureSelected(boolean isFeatureSelected) {
                this.isFeatureSelected = isFeatureSelected;
                return this;
            }

            public Builder isUnderSampled(boolean isUnderSampled) {
                this.isUnderSampled = isUnderSampled;
                return this;
            }

            public Builder featureSelectionId(String featureSelectionId) {
                this.featureSelectionId = featureSelectionId;
                return this;
            }

            public EvaluationConfig build() {
                return new EvaluationConfig(this);
            }
        }

        // Getters
        public String getClassifierType() { return classifierType; }
        public Classifier getClassifier() { return classifier; }
        public Instances getTrainSet() { return trainSet; }
        public Instances getTestSet() { return testSet; }
        public int getIndex() { return index; }
        public boolean isFeatureSelected() { return isFeatureSelected; }
        public boolean isUnderSampled() { return isUnderSampled; }
        public String getFeatureSelectionId() { return featureSelectionId; }
    }
    private ResultsHolder evaluateClassifierWithFeatureSelectionId(EvaluationConfig config) {
        ResultsHolder defaultResult = new ResultsHolder(
                config.getIndex(),
                config.getClassifierType(),
                config.isFeatureSelected(),
                config.isUnderSampled(),
                false,
                config.getFeatureSelectionId()
        );

        if (!validateTrainingSet(config.getTrainSet(), config.getClassifierType(), config.getIndex())) {
            return defaultResult;
        }

        try {
            Evaluation eval = performEvaluation(config.getClassifier(), config.getTrainSet(), config.getTestSet());
            displayResultsIfRandomForest(config.getClassifierType(), eval, config.getTrainSet(),
                    config.getTestSet(), config.getClassifier(), config.getIndex());
            populateResults(defaultResult, eval, config.getTrainSet());
            return defaultResult;

        } catch (Exception e) {
            handleEvaluationError(config.getClassifierType(), config.getIndex(), e);
            return defaultResult;
        }
    }


    private boolean validateTrainingSet(Instances trainSet, String classifierType, int index) {
        if (trainSet.numInstances() < 2) {
            out.println(String.format(WARNING_NOT_ENOUGH_INSTANCES_MSG,
                    classifierType.toUpperCase(), index, ""));
            return false;
        }
        return true;
    }

    private Evaluation performEvaluation(Classifier classifier, Instances trainSet, Instances testSet)
            throws Exception {
        classifier.buildClassifier(trainSet);
        Evaluation eval = new Evaluation(trainSet);
        eval.evaluateModel(classifier, testSet);
        return eval;
    }

    private void displayResultsIfRandomForest(String classifierType, Evaluation eval,
                                              Instances trainSet, Instances testSet, Classifier classifier, int index) {
        if ("rf".equals(classifierType)) {
            displayDetailedEvaluation(eval, trainSet, testSet, classifier, index);
        }
    }



    // Per la gestione degli errori generici
    private void handleEvaluationError(String operation, int index, Exception e) {
        out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, operation, index, e.getMessage()));
    }




    private AttributeSelection getFilter(Instances trainSet) throws Exception {
        // Use the release index as a key for caching
        // We extract it from the relation name which contains the release number
        String relationName = trainSet.relationName();
        int releaseIndex = -1;

        // Try to extract the release number from the relation name
        if (relationName.contains("_R")) {
            try {
                String releaseStr = relationName.substring(relationName.indexOf("_R") + 2);
                // Check for both "_" and "-" as potential delimiters
                if (releaseStr.contains("_")) {
                    releaseStr = releaseStr.substring(0, releaseStr.indexOf("_"));
                } else if (releaseStr.contains("-")) {
                    releaseStr = releaseStr.substring(0, releaseStr.indexOf("-"));
                }
                releaseIndex = Integer.parseInt(releaseStr);
            } catch (Exception e) {
                // If we can't parse the release number, we'll just create a new filter
                out.println(String.format(RELEASE_NUMBER_WARNING_MSG, relationName));
            }
        }

        // Check if we have a cached filter for this release
        if (releaseIndex != -1 && featureSelectionCache.containsKey(releaseIndex)) {
            out.println(String.format(CACHED_FILTER_MSG, releaseIndex));
            return featureSelectionCache.get(releaseIndex);
        }

        // Create a new filter
        out.println(String.format(NEW_FILTER_MSG, releaseIndex != -1 ? " for release " + releaseIndex : ""));
        CfsSubsetEval eval = new CfsSubsetEval();
        AttributeSelection filter = new AttributeSelection();
        GreedyStepwise searcher = new GreedyStepwise();
        searcher.setSearchBackwards(true);
        filter.setSearch(searcher);
        filter.setEvaluator(eval);
        filter.setInputFormat(trainSet);

        // Apply the filter to get selected attributes
        Instances filteredData = Filter.useFilter(trainSet, filter);

        // Log the selected and discarded features
        logFeatureSelection(trainSet, filteredData, releaseIndex);

        // Cache the filter if we have a valid release index
        if (releaseIndex != -1) {
            featureSelectionCache.put(releaseIndex, filter);
        }

        return filter;
    }

    /**
     * Logs the features that were selected and discarded during feature selection.
     *
     * @param originalData The original dataset before feature selection
     * @param filteredData The dataset after feature selection
     * @param releaseIndex The release index for which feature selection was performed
     * @return The unique ID generated for this feature selection
     */
    private String logFeatureSelection(Instances originalData, Instances filteredData, int releaseIndex) {
        StringBuilder logMessage = new StringBuilder();

        // Generate a unique ID for this feature selection
        String featureSelectionId = generateFeatureSelectionId(filteredData, releaseIndex);

        logMessage.append(String.format(FEATURE_SELECTION_LOG_MSG, releaseIndex));
        logMessage.append("Feature Selection ID: ").append(featureSelectionId).append("\n");

        // Get all attributes from original data (excluding class attribute)
        ArrayList<Attribute> originalAttributes = new ArrayList<>();
        for (int i = 0; i < originalData.numAttributes() - 1; i++) {
            originalAttributes.add(originalData.attribute(i));
        }

        // Get all attributes from filtered data (excluding class attribute)
        ArrayList<Attribute> filteredAttributes = new ArrayList<>();
        for (int i = 0; i < filteredData.numAttributes() - 1; i++) {
            filteredAttributes.add(filteredData.attribute(i));
        }

        // Find selected features
        logMessage.append("Selected Features (").append(filteredAttributes.size()).append("):\n");
        for (Attribute attr : filteredAttributes) {
            logMessage.append("  - ").append(attr.name()).append("\n");
        }

        // Find discarded features
        ArrayList<Attribute> discardedAttributes = new ArrayList<>(originalAttributes);
        discardedAttributes.removeAll(filteredAttributes);

        logMessage.append("Discarded Features (").append(discardedAttributes.size()).append("):\n");
        for (Attribute attr : discardedAttributes) {
            logMessage.append("  - ").append(attr.name()).append("\n");
        }

        // Print the log message
        out.println(logMessage.toString());

        // Optionally, write to a file
        try {
            String fileName = "feature_selection_log_release_" + releaseIndex + ".txt";
            try (FileWriter writer = new FileWriter(fileName, true)) {
                writer.write(logMessage.toString());
                writer.write("\n");
            }
            out.println(String.format(FEATURE_LOG_WRITTEN_MSG, fileName));
        } catch (IOException e) {
            out.println(String.format(FEATURE_LOG_ERROR_MSG, e.getMessage()));
        }

        return featureSelectionId;
    }

    /**
     * Generates a unique ID for a feature selection based on the selected features and release index.
     *
     * @param filteredData The dataset after feature selection
     * @param releaseIndex The release index for which feature selection was performed
     * @return A unique ID for this feature selection
     */
    private String generateFeatureSelectionId(Instances filteredData, int releaseIndex) {
        // Create a sorted list of selected feature names
        List<String> selectedFeatures = new ArrayList<>();
        for (int i = 0; i < filteredData.numAttributes() - 1; i++) {
            selectedFeatures.add(filteredData.attribute(i).name());
        }
        Collections.sort(selectedFeatures);

        // Create a key based on the sorted feature names
        String featuresKey = String.join(",", selectedFeatures);

        // Check if we already have an ID for this combination of release and features
        featureSelectionIdCache.computeIfAbsent(releaseIndex, k -> new HashMap<>());
        Map<String, String> releaseIdMap = featureSelectionIdCache.get(releaseIndex);
        releaseIdMap.computeIfAbsent(featuresKey, k ->
                "FS_R" + releaseIndex + "_" + (releaseIdMap.size() + 1)
        );

        return releaseIdMap.get(featuresKey);
    }

    /**
     * Gets the feature selection ID for a given release and filtered data.
     *
     * @param filteredData The dataset after feature selection
     * @param releaseIndex The release index for which feature selection was performed
     * @return The feature selection ID, or an empty string if not found
     */
    private String getFeatureSelectionId(Instances filteredData, int releaseIndex) {
        if (releaseIndex < 0 || !featureSelectionIdCache.containsKey(releaseIndex)) {
            return "";
        }

        // Create a sorted list of selected feature names
        List<String> selectedFeatures = new ArrayList<>();
        for (int i = 0; i < filteredData.numAttributes() - 1; i++) {
            selectedFeatures.add(filteredData.attribute(i).name());
        }
        Collections.sort(selectedFeatures);

        // Create a key based on the sorted feature names
        String featuresKey = String.join(",", selectedFeatures);

        Map<String, String> releaseIdMap = featureSelectionIdCache.get(releaseIndex);
        return releaseIdMap.getOrDefault(featuresKey, "");
    }

    //metodo che addestra i classificatori con sampling e feature selection.
//Effettua un passo del walk forward per i tre classificatori
    public void evalUnderSampFeatureSelection(Instances trainSet, Instances testSet, int index) {
        if (!validateDatasets(trainSet, testSet, index, "under-sampling with feature selection")) {
            return;
        }

        ensureValidClassIndices(trainSet, testSet);

        try {
            ProcessedDatasets processedData = applyFeatureSelection(trainSet, testSet, index);
            if (processedData == null) {
                return;
            }

            // Get the feature selection ID for this filtered dataset
            String featureSelectionId = getFeatureSelectionId(processedData.filteredTrainSet, index);

            Instances underSampledSet = applyUnderSampling(processedData.filteredTrainSet, index);
            evalStandardWithFeatureSelectionId(underSampledSet, processedData.filteredTestSet, index, true, true, false, featureSelectionId);

        } catch (Exception e) {
            handleEvaluationError("under-sampling with feature selection", index, e);
        }
    }

    private boolean validateDatasets(Instances trainSet, Instances testSet, int index, String evaluationType) {
        if (trainSet == null || trainSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TRAIN_SET_MSG, index, evaluationType));
            return false;
        }
        if (testSet == null || testSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TEST_SET_MSG, index, evaluationType));
            return false;
        }
        return true;
    }

    private ProcessedDatasets applyFeatureSelection(Instances trainSet, Instances testSet, int index) {
        try {
            AttributeSelection filter = getFilter(trainSet);
            Instances filteredTrainSet = Filter.useFilter(trainSet, filter);
            Instances filteredTestSet = Filter.useFilter(testSet, filter);

            if (!validateFilteredDatasets(filteredTrainSet, filteredTestSet, index)) {
                return null;
            }

            setClassIndices(filteredTrainSet, filteredTestSet);


            return new ProcessedDatasets(filteredTrainSet, filteredTestSet);

        } catch (Exception e) {
            handleEvaluationError(FEATURE_SELECTION, index, e);
            return null;
        }
    }

    private boolean validateFilteredDatasets(Instances filteredTrain, Instances filteredTest, int index) {
        if (filteredTrain == null || filteredTrain.numInstances() == 0) {
            out.println(String.format(WARNING_FEATURE_SELECTION_RESULT_MSG, EMPTY_TRAINING_DATASET_MSG, index));
            return false;
        }
        if (filteredTest == null || filteredTest.numInstances() == 0) {
            out.println(String.format(WARNING_FEATURE_SELECTION_RESULT_MSG, EMPTY_TEST_DATASET_MSG, index));
            return false;
        }
        if (filteredTrain.numAttributes() < 2) {
            out.println(String.format(WARNING_TOO_FEW_ATTRIBUTES_MSG, index));
            return false;
        }
        return true;
    }

    private void setClassIndices(Instances filteredTrain, Instances filteredTest) {
        int numAttrFiltered = filteredTrain.numAttributes();
        filteredTrain.setClassIndex(numAttrFiltered - 1);
        filteredTest.setClassIndex(numAttrFiltered - 1);
    }

    private Instances applyUnderSampling(Instances dataset, int index) {
        try {
            SpreadSubsample filterSample = new SpreadSubsample();
            filterSample.setInputFormat(dataset);
            filterSample.setDistributionSpread(1.0);
            Instances underSampledSet = Filter.useFilter(dataset, filterSample);

            if (underSampledSet == null || underSampledSet.numInstances() == 0) {
                out.println(String.format(WARNING_UNDERSAMPLING_RESULT_MSG, index));
                return new Instances(dataset);
            }
            return underSampledSet;

        } catch (Exception e) {
            handleEvaluationError("under-sampling", index, e);
            return new Instances(dataset);
        }
    }



    private static class ProcessedDatasets {
        final Instances filteredTrainSet;
        final Instances filteredTestSet;

        ProcessedDatasets(Instances filteredTrain, Instances filteredTest) {
            this.filteredTrainSet = filteredTrain;
            this.filteredTestSet = filteredTest;
        }
    }


    //metodo che addestra i classificatori con cost sensitive e feature selection.
//Effettua un passo del walk forward per i tre classificatori
    public void evalCostFeatureSelection(Instances trainSet, Instances testSet, int index) throws Exception {
        // Validate datasets before processing
        if (trainSet == null || trainSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TRAIN_SET_MSG, index, EXPENSIVE_FEATURE_SELECTION));
            return;
        }

        if (testSet == null || testSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TEST_SET_MSG, index, EXPENSIVE_FEATURE_SELECTION));
            return;
        }

        // Ensure class attribute is set and valid
        if (trainSet.classIndex() < 0 || trainSet.classIndex() >= trainSet.numAttributes()) {
            out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, "training", index));
            trainSet.setClassIndex(trainSet.numAttributes() - 1);
        }

        if (testSet.classIndex() < 0 || testSet.classIndex() >= testSet.numAttributes()) {
            out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, "test", index));
            testSet.setClassIndex(testSet.numAttributes() - 1);
        }

        try {
            AttributeSelection filter = getFilter(trainSet);

            Instances filteredTrainSet = Filter.useFilter(trainSet, filter);
            Instances filteredTestSet = Filter.useFilter(testSet, filter);

            // Validate the filtered sets before proceeding
            if (filteredTrainSet == null || filteredTrainSet.numInstances() == 0) {
                out.println(String.format(WARNING_FEATURE_SELECTION_RESULT_MSG, EMPTY_TRAINING_DATASET_MSG, index));
                return;
            }

            if (filteredTestSet == null || filteredTestSet.numInstances() == 0) {
                out.println(String.format(WARNING_FEATURE_SELECTION_RESULT_MSG,EMPTY_TEST_DATASET_MSG, index));
                return;
            }

            // Ensure filtered datasets have at least one attribute plus class
            if (filteredTrainSet.numAttributes() < 2) {
                out.println(String.format(WARNING_TOO_FEW_ATTRIBUTES_MSG, index));
                return;
            }

            int numAttrFiltered = filteredTrainSet.numAttributes();
            filteredTrainSet.setClassIndex(numAttrFiltered - 1);
            filteredTestSet.setClassIndex(numAttrFiltered - 1);

            // Get the feature selection ID for this filtered dataset
            String featureSelectionId = getFeatureSelectionId(filteredTrainSet, index);

            evalCostSensitiveWithFeatureSelectionId(filteredTrainSet, filteredTestSet, index, true, featureSelectionId);
        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, EXPENSIVE_FEATURE_SELECTION, index, e.getMessage()));
            // Continue with execution rather than throwing the exception
        }
    }

    /**
     * Variant of evalCostSensitive that also sets the feature selection ID.
     */
    public void evalCostSensitiveWithFeatureSelectionId(Instances trainSet, Instances testSet, int index, boolean isFeatureSelected, String featureSelectionId)  {
        if (!validateDatasets(trainSet, testSet, index)) {
            return;
        }

        ensureValidClassIndices(trainSet, testSet);

        try {
            CostSensitiveClassifier costSensitiveClassifier = createCostSensitiveClassifier();
            Map<String, Classifier> threadLocalClassifiers = createThreadLocalClassifiers();

            List<ResultsHolder> results = evaluateAllClassifiersWithFeatureSelectionId(
                    costSensitiveClassifier, threadLocalClassifiers, trainSet, testSet, index, isFeatureSelected, featureSelectionId);

            // Aggiunto false per isUnderSampled e isOverSampled poiché non applicabili per cost-sensitive
            saveResults(results, isFeatureSelected, false, false);

        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, "cost-sensitive", index, e.getMessage()));
        }
    }

    private List<ResultsHolder> evaluateAllClassifiersWithFeatureSelectionId(
            CostSensitiveClassifier costSensitiveClassifier,
            Map<String, Classifier> classifiers,
            Instances trainSet,
            Instances testSet,
            int index,
            boolean isFeatureSelected,
            String featureSelectionId) {

        DatasetConfig datasetConfig = new DatasetConfig(trainSet, testSet, costSensitiveClassifier, null);

        return classifiers.entrySet().stream()
                .map(entry -> {
                    datasetConfig.baseClassifier = entry.getValue();
                    ClassifierConfig classifierConfig = new ClassifierConfig(
                            entry.getKey(),
                            isFeatureSelected,
                            featureSelectionId,
                            index
                    );
                    return evaluateSingleClassifierWithFeatureSelectionId(classifierConfig, datasetConfig);
                })
                .toList();
    }

    private static class ClassifierConfig {
        final String classifierType;
        final boolean isFeatureSelected;
        final String featureSelectionId;
        final int index;

        ClassifierConfig(String classifierType, boolean isFeatureSelected, String featureSelectionId, int index) {
            this.classifierType = classifierType;
            this.isFeatureSelected = isFeatureSelected;
            this.featureSelectionId = featureSelectionId;
            this.index = index;
        }
    }

    private static class DatasetConfig {
        final Instances trainSet;
        final Instances testSet;
        final CostSensitiveClassifier costSensitiveClassifier;
        Classifier baseClassifier;

        DatasetConfig(Instances trainSet, Instances testSet,
                      CostSensitiveClassifier costSensitiveClassifier, Classifier baseClassifier) {
            this.trainSet = trainSet;
            this.testSet = testSet;
            this.costSensitiveClassifier = costSensitiveClassifier;
            this.baseClassifier = baseClassifier;
        }
    }

    private ResultsHolder evaluateSingleClassifierWithFeatureSelectionId(
            ClassifierConfig classifierConfig, DatasetConfig datasetConfig) {

        try {
            Evaluation eval = buildAndEvaluateClassifier(datasetConfig);
            handleRandomForestOutput(classifierConfig, datasetConfig, eval);
            return buildResultsHolder(classifierConfig, eval, datasetConfig.trainSet);

        } catch (Exception e) {
            handleEvaluationError(classifierConfig, e);
            return new ResultsHolder(classifierConfig.index, classifierConfig.classifierType,
                    classifierConfig.isFeatureSelected, false, true, classifierConfig.featureSelectionId);
        }
    }

    private Evaluation buildAndEvaluateClassifier(DatasetConfig config) throws Exception {
        config.costSensitiveClassifier.setClassifier(config.baseClassifier);
        config.costSensitiveClassifier.buildClassifier(config.trainSet);

        Evaluation eval = new Evaluation(config.trainSet);
        eval.evaluateModel(config.costSensitiveClassifier, config.testSet);
        return eval;
    }

    private void handleRandomForestOutput(ClassifierConfig config,
                                          DatasetConfig dataConfig, Evaluation eval) {
        if ("rf".equals(config.classifierType)) {
            out.println("\n=== Cost-Sensitive Evaluation ===");
            displayDetailedEvaluation(eval, dataConfig.trainSet, dataConfig.testSet,
                    dataConfig.costSensitiveClassifier, config.index);
        }
    }

    private ResultsHolder buildResultsHolder(ClassifierConfig config,
                                             Evaluation eval, Instances trainSet) {
        ResultsHolder results = new ResultsHolder(config.index, config.classifierType,
                config.isFeatureSelected, false, true, config.featureSelectionId);
        populateResults(results, eval, trainSet);
        return results;
    }

    private void handleEvaluationError(ClassifierConfig config, Exception e) {
        if (!"rf".equals(config.classifierType)) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG,
                    config.classifierType.toUpperCase() + " cost-sensitive",
                    config.index, e.getMessage()));
            return;
        }

        String errorMessage = e instanceof ArrayIndexOutOfBoundsException ?
                formatArrayIndexError(e, config.index) :
                String.format(ERROR_RF_COST_SENSITIVE_EVALUATION_MSG,
                        config.index, e.getMessage());

        out.println(errorMessage);
        out.println("Stack trace for RF cost-sensitive evaluation error:");
        e.printStackTrace(out);
    }

    private String formatArrayIndexError(Exception e, int index) {
        try {
            String[] parts = e.getMessage().split(" ");
            String indexValue = parts.length > 0 ? parts[0] : UNKNOWN_LITERAL;
            String lengthValue = parts.length > 5 ? parts[5] : UNKNOWN_LITERAL;
            return String.format("Error in RF cost-sensitive evaluation for release %d: Index %s out of bounds for length %s",
                    index, indexValue, lengthValue);
        } catch (Exception parseEx) {
            return String.format(ERROR_RF_COST_SENSITIVE_EVALUATION_MSG,
                    index, e.getMessage());
        }
    }

    //metodo che addestra i classificatori con cost sensitive e under sampling.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalCostUnderSampling(Instances trainSet, Instances testSet, int index) throws Exception {
        // Validate datasets before processing
        if (trainSet == null || trainSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TRAIN_SET_MSG, index, "cost sensitive under sampling"));
            return;
        }

        if (testSet == null || testSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TEST_SET_MSG, index, "cost sensitive under sampling"));
            return;
        }

        // Ensure class attribute is set and valid
        if (trainSet.classIndex() < 0 || trainSet.classIndex() >= trainSet.numAttributes()) {
            out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, DataSetType.TRAINING, index));
            trainSet.setClassIndex(trainSet.numAttributes() - 1);
        }

        try {
            SpreadSubsample filter = new SpreadSubsample();
            filter.setInputFormat(trainSet);
            filter.setDistributionSpread(1.0);
            Instances underSampledSet = Filter.useFilter(trainSet, filter);

            // Validate the under-sampled set before proceeding
            if (underSampledSet == null || underSampledSet.numInstances() == 0) {
                out.println(String.format(WARNING_UNDERSAMPLING_RESULT_MSG, index));
                return;
            }

            evalCostSensitive(underSampledSet, testSet, index, false);
        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, "cost sensitive under-sampling", index, e.getMessage()));
            // Continue with execution rather than throwing the exception
        }
    }

    //metodo che addestra i classificatori con cost sensitive e over sampling.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalCostOverSampling(Instances trainSet, Instances testSet, int index) throws Exception {
        // Validate dataset before processing
        if (trainSet == null || trainSet.numInstances() == 0) {
            out.println(String.format(WARNING_EMPTY_TRAIN_SET_MSG, index, "cost sensitive oversampling"));
            return;
        }

        try {
            Resample filter = new Resample();
            filter.setBiasToUniformClass(1.0);
            filter.setNoReplacement(false);

            int numAllInstances = trainSet.numInstances();
            int classMajorIndex = trainSet.classAttribute().indexOfValue(FALSE_LITERAL);
            int numMajorInstances = 0;

            // Check if "false" value exists in the class attribute
            if (classMajorIndex == -1) {
                // If "false" doesn't exist, assume the first value is the majority class
                out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, DataSetType.TRAINING, index));
                classMajorIndex = 0;
            }

            for (int i = 0; i < numAllInstances; i++) {
                if (trainSet.instance(i).classValue() == classMajorIndex) {
                    numMajorInstances++;
                }
            }

            // Ensure we have a positive sample size to avoid "bound must be positive" errors
            double sampleSize = ((double) numMajorInstances / numAllInstances) * 2 * 100;
            if (sampleSize <= 0) {
                out.println(String.format(WARNING_NOT_ENOUGH_INSTANCES_MSG, "cost sensitive " + OVERSAMPLING, index, USING_DEFAULT_100_PERCENT));
                sampleSize = 100.0; // Default to 100% if calculation results in non-positive value
            }

            filter.setSampleSizePercent(sampleSize);
            filter.setInputFormat(trainSet);
            Instances overSampledSet = Filter.useFilter(trainSet, filter);

            // Validate the oversampled set before proceeding
            if (overSampledSet == null || overSampledSet.numInstances() == 0) {
                out.println(String.format(WARNING_OVERSAMPLING_RESULT_MSG, index));
                return;
            }

            evalCostSensitive(overSampledSet, testSet, index, false);
        } catch (Exception e) {
            out.println(String.format(ERROR_CLASSIFIER_EVALUATION_MSG, "cost sensitive " + OVERSAMPLING, index, e.getMessage()));
            // Continue with execution rather than throwing the exception
        }
    }

    //metodo che addestra i classificatori con cost sensitive, under sampling e feature selection.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalCostUnderSampFeatureSelection(Instances trainSet, Instances testSet, int index) {
        if (!validateDatasets(trainSet, testSet, index, "cost sensitive under-sampling with feature selection")) {
            return;
        }

        ensureValidClassIndices(trainSet, testSet);

        try {
            ProcessedDatasets processedData = applyFeatureSelection(trainSet, testSet, index);
            if (processedData == null) {
                return;
            }

            // Get the feature selection ID for this filtered dataset
            String featureSelectionId = getFeatureSelectionId(processedData.filteredTrainSet, index);

            Instances underSampledSet = applyUnderSampling(processedData.filteredTrainSet, index);
            evalCostSensitiveWithFeatureSelectionId(underSampledSet, processedData.filteredTestSet, index, true, featureSelectionId);

        } catch (Exception e) {
            handleEvaluationError("cost sensitive under-sampling with feature selection", index, e);
        }
    }

    //metodo che addestra i classificatori con cost sensitive, over sampling e feature selection.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalCostOverSampFeatureSelection(Instances trainSet, Instances testSet, int index) {
        if (!validateDatasets(trainSet, testSet, index, "cost sensitive over-sampling with feature selection")) {
            return;
        }

        ensureValidClassIndices(trainSet, testSet);

        try {
            ProcessedDatasets processedData = applyFeatureSelection(trainSet, testSet, index);
            if (processedData == null) {
                return;
            }

            // Get the feature selection ID for this filtered dataset
            String featureSelectionId = getFeatureSelectionId(processedData.filteredTrainSet, index);

            Instances overSampledSet = applyOverSampling(processedData.filteredTrainSet, index);
            evalCostSensitiveWithFeatureSelectionId(overSampledSet, processedData.filteredTestSet, index, true, featureSelectionId);

        } catch (Exception e) {
            handleEvaluationError("cost sensitive over-sampling with feature selection", index, e);
        }
    }

    //metodo che addestra i classificatori con over sampling e feature selection.
    //Effettua un passo del walk forward per i tre classificatori
    public void evalOverSampFeatureSelection(Instances trainSet, Instances testSet, int index) {
        if (!validateDatasets(trainSet, testSet, index, "over-sampling with feature selection")) {
            return;
        }

        ensureValidClassIndices(trainSet, testSet);

        try {
            ProcessedDatasets processedData = applyFeatureSelection(trainSet, testSet, index);
            if (processedData == null) {
                return;
            }

            // Get the feature selection ID for this filtered dataset
            String featureSelectionId = getFeatureSelectionId(processedData.filteredTrainSet, index);

            Instances overSampledSet = applyOverSampling(processedData.filteredTrainSet, index);
            evalStandardWithFeatureSelectionId(overSampledSet, processedData.filteredTestSet, index, true, false, true, featureSelectionId);

        } catch (Exception e) {
            handleEvaluationError("over-sampling with feature selection", index, e);
        }
    }

    private Instances applyOverSampling(Instances dataset, int index) {
        try {
            Resample filter = new Resample();
            filter.setBiasToUniformClass(1.0);
            filter.setNoReplacement(false);

            int numAllInstances = dataset.numInstances();
            int classMajorIndex = dataset.classAttribute().indexOfValue(FALSE_LITERAL);
            int numMajorInstances = 0;

            // Check if "false" value exists in the class attribute
            if (classMajorIndex == -1) {
                // If "false" doesn't exist, assume the first value is the majority class
                out.println(String.format(WARNING_INVALID_CLASS_INDEX_MSG, DataSetType.TRAINING, index));
                classMajorIndex = 0;
            }

            for (int i = 0; i < numAllInstances; i++) {
                if (dataset.instance(i).classValue() == classMajorIndex) {
                    numMajorInstances++;
                }
            }

            // Ensure we have a positive sample size to avoid "bound must be positive" errors
            double sampleSize = ((double) numMajorInstances / numAllInstances) * 2 * 100;
            if (sampleSize <= 0) {
                out.println(String.format(WARNING_NOT_ENOUGH_INSTANCES_MSG, OVERSAMPLING, index, USING_DEFAULT_100_PERCENT));
                sampleSize = 100.0; // Default to 100% if calculation results in non-positive value
            }

            filter.setSampleSizePercent(sampleSize);
            filter.setInputFormat(dataset);
            Instances overSampledSet = Filter.useFilter(dataset, filter);

            if (overSampledSet == null || overSampledSet.numInstances() == 0) {
                out.println(String.format(WARNING_OVERSAMPLING_RESULT_MSG, index));
                return new Instances(dataset);
            }
            return overSampledSet;

        } catch (Exception e) {
            handleEvaluationError(OVERSAMPLING, index, e);
            return new Instances(dataset);
        }
    }

    //metodo che prende i risultati e li salva su un csv
    public void csvWriter(List<List<ResultsHolder>> list){
        String path =Projects.getTrainProject()+"ResultsForJMP.csv";
        try (FileWriter writer = new FileWriter(path)) {

            writer.write("Classifier,feature selection,sampling,cost sensitive,feature selection id,precision,recall,auc,kappa\n");

            for(List<ResultsHolder> miniList:list){
                for(ResultsHolder miniMiniList:miniList){
                    writer.write(miniMiniList.getClassifier()+","+miniMiniList.isFeatureSelection()+","+
                            miniMiniList.isSampling()+","+miniMiniList.isCostSensitive()+","+
                            miniMiniList.getFeatureSelectionId()+","+
                            miniMiniList.getPrecision()+","+miniMiniList.getRecall()+","+
                            miniMiniList.getAuc()+","+miniMiniList.getKappa()+"\n");
                }
            }

            out.println(CSV_SUCCESS_MSG);
        } catch (IOException e) {
            out.printf((CSV_ERROR_MSG) + "%n", e.getMessage());
        }
    }

    public ResultsHolder avgCalculator(List<ResultsHolder> list){
        int len = list.size();
        String classifier = list.get(0).getClassifier();
        boolean isFeatureSelected = list.get(0).isFeatureSelection();
        boolean isSampled = list.get(0).isSampling();
        boolean isCostSens = list.get(0).isCostSensitive();
        double precision = 0;
        double recall = 0;
        double auc = 0;
        double kappa = 0;
        for(ResultsHolder r: list){
            precision = precision + r.getPrecision();
            recall = recall + r.getRecall();
            auc = auc + r.getAuc();
            kappa = kappa + r.getKappa();
        }
        precision = precision / len;
        recall = recall / len;
        auc = auc / len;
        kappa = kappa / len;

        ResultsHolder avgResult = new ResultsHolder(-1,classifier,isFeatureSelected,isSampled,isCostSens);
        avgResult.setPrecision(precision);
        avgResult.setRecall(recall);
        avgResult.setAuc(auc);
        avgResult.setKappa(kappa);

        return avgResult;
    }
}
